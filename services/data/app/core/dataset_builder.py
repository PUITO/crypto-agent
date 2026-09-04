"""
训练数据集构建 + 上传 Hugging Face。

功能：
1. 从本地 Parquet 读取 K 线
2. 计算常用技术指标特征
3. 生成标签（例如未来 N 根 K 线的涨跌）
4. 保存为本地数据集
5. 推送到 Hugging Face Hub，方便其他项目/设备直接使用
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import numpy as np
import pandas as pd

from common.logging import get_logger

from .storage import ParquetStorage

logger = get_logger("data-service.dataset")


def _rsi(series: pd.Series, period: int = 14) -> pd.Series:
    delta = series.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)
    avg_gain = gain.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))


def _ema(series: pd.Series, period: int) -> pd.Series:
    return series.ewm(span=period, adjust=False).mean()


def compute_features(df: pd.DataFrame) -> pd.DataFrame:
    """计算常用技术指标特征"""
    out = df.copy()
    close = out["close"]
    high = out["high"]
    low = out["low"]
    volume = out["volume"]

    # 收益与波动
    out["return_1"] = close.pct_change(1)
    out["return_3"] = close.pct_change(3)
    out["return_6"] = close.pct_change(6)
    out["volatility_12"] = out["return_1"].rolling(12).std()

    # 均线
    out["ema_9"] = _ema(close, 9)
    out["ema_21"] = _ema(close, 21)
    out["ema_55"] = _ema(close, 55)
    out["ema_ratio_9_21"] = out["ema_9"] / out["ema_21"] - 1

    # RSI
    out["rsi_14"] = _rsi(close, 14)
    out["rsi_6"] = _rsi(close, 6)

    # 布林带位置
    mid = close.rolling(20).mean()
    std = close.rolling(20).std()
    out["bb_mid"] = mid
    out["bb_upper"] = mid + 2 * std
    out["bb_lower"] = mid - 2 * std
    out["bb_pct"] = (close - out["bb_lower"]) / (out["bb_upper"] - out["bb_lower"]).replace(0, np.nan)

    # 成交量变化
    out["volume_ma_20"] = volume.rolling(20).mean()
    out["volume_ratio"] = volume / out["volume_ma_20"].replace(0, np.nan)

    # KDJ (简化版)
    low_min = low.rolling(9).min()
    high_max = high.rolling(9).max()
    rsv = (close - low_min) / (high_max - low_min).replace(0, np.nan) * 100
    out["kdj_k"] = rsv.ewm(com=2, adjust=False).mean()
    out["kdj_d"] = out["kdj_k"].ewm(com=2, adjust=False).mean()
    out["kdj_j"] = 3 * out["kdj_k"] - 2 * out["kdj_d"]

    return out


def add_labels(df: pd.DataFrame, horizon: int = 6, threshold: float = 0.0) -> pd.DataFrame:
    """
    添加标签。
    horizon: 未来看几根 K 线（5m 周期下 6 根 ≈ 30 分钟，适合事件合约）
    label_up: 未来 horizon 根收盘价相对当前是否上涨超过 threshold
    future_return: 未来 horizon 根的真实收益率
    """
    out = df.copy()
    future_close = out["close"].shift(-horizon)
    out["future_return"] = future_close / out["close"] - 1
    out["label_up"] = (out["future_return"] > threshold).astype("Int64")  # 可空整数
    # 最后 horizon 行没有未来数据，标签设为 NA
    return out


class DatasetBuilder:
    def __init__(
        self,
        storage: ParquetStorage,
        output_dir: str | Path = "./data/datasets",
        hf_repo_id: Optional[str] = None,
        hf_token: Optional[str] = None,
    ):
        self.storage = storage
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.hf_repo_id = hf_repo_id or os.getenv("HF_REPO_ID")
        self.hf_token = hf_token or os.getenv("HF_TOKEN") or os.getenv("HUGGINGFACE_TOKEN")

    def build(
        self,
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
        horizon: int = 6,
        dropna: bool = True,
    ) -> pd.DataFrame:
        """从本地 K 线构建带特征与标签的数据集"""
        df = self.storage.load_klines(symbol, interval, start=start, end=end)
        if df.empty:
            logger.warning(f"No kline data for {symbol} {interval}")
            return df

        df = compute_features(df)
        df = add_labels(df, horizon=horizon)

        if dropna:
            # 只丢掉特征全空的行，标签 NA 的（尾部）也去掉
            feature_cols = [c for c in df.columns if c not in ("open_time", "close_time", "symbol", "interval")]
            df = df.dropna(subset=feature_cols).reset_index(drop=True)

        logger.info(f"Built dataset: {len(df)} rows, {len(df.columns)} columns for {symbol} {interval}")
        return df

    def save_local(self, df: pd.DataFrame, name: str) -> Path:
        """保存为本地文件 + 简单元数据（优先 parquet，否则 pickle）"""
        import json
        try:
            import pyarrow  # noqa: F401
            path = self.output_dir / f"{name}.parquet"
            df.to_parquet(path, index=False)
        except ImportError:
            path = self.output_dir / f"{name}.pkl"
            df.to_pickle(path)
        meta = {
            "name": name,
            "rows": len(df),
            "columns": list(df.columns),
            "created_at": datetime.now(timezone.utc).isoformat(),
            "path": str(path),
        }
        meta_path = self.output_dir / f"{name}.meta.json"
        meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
        logger.info(f"Dataset saved locally: {path}")
        return path

    def push_to_hub(
        self,
        df: pd.DataFrame,
        repo_id: Optional[str] = None,
        private: bool = False,
        commit_message: str = "Update crypto kline dataset",
    ) -> dict[str, Any]:
        """
        将数据集推送到 Hugging Face Hub。
        需要环境变量 HF_TOKEN 或传入 hf_token。
        """
        repo_id = repo_id or self.hf_repo_id
        token = self.hf_token

        if not repo_id:
            raise ValueError("hf_repo_id is required (or set HF_REPO_ID env)")
        if not token:
            raise ValueError("Hugging Face token is required (set HF_TOKEN or HUGGINGFACE_TOKEN env)")

        try:
            from datasets import Dataset
            from huggingface_hub import HfApi, login
        except ImportError as e:
            raise ImportError(
                "Please install: pip install datasets huggingface_hub"
            ) from e

        login(token=token, add_to_git_credential=False)

        # 转为 Hugging Face Dataset（处理时间列）
        df_upload = df.copy()
        for col in ("open_time", "close_time"):
            if col in df_upload.columns:
                df_upload[col] = df_upload[col].astype(str)

        ds = Dataset.from_pandas(df_upload, preserve_index=False)

        # 推送
        ds.push_to_hub(
            repo_id,
            private=private,
            token=token,
            commit_message=commit_message,
        )

        url = f"https://huggingface.co/datasets/{repo_id}"
        logger.info(f"Dataset pushed to Hugging Face: {url}")
        return {
            "repo_id": repo_id,
            "url": url,
            "rows": len(df),
            "columns": list(df.columns),
        }

    def build_and_push(
        self,
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        horizon: int = 6,
        repo_id: Optional[str] = None,
        private: bool = False,
    ) -> dict[str, Any]:
        """一站式：构建 → 本地保存 → 推送到 HF"""
        df = self.build(symbol=symbol, interval=interval, horizon=horizon)
        if df.empty:
            return {"ok": False, "message": "empty dataset"}

        ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M")
        name = f"{symbol.lower()}_{interval}_h{horizon}_{ts}"
        local_path = self.save_local(df, name)

        result = {
            "ok": True,
            "local_path": str(local_path),
            "rows": len(df),
            "columns": list(df.columns),
        }

        try:
            hf_info = self.push_to_hub(df, repo_id=repo_id, private=private)
            result["huggingface"] = hf_info
        except Exception as e:
            logger.warning(f"Push to Hugging Face failed (local saved): {e}")
            result["huggingface_error"] = str(e)

        return result
