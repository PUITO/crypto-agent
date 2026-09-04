"""
本地存储管理。
优先尝试 Parquet（需 pyarrow），不可用时自动降级为 pickle / CSV.gz。
按 symbol / interval / 年月 分区。
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import pandas as pd

from common.logging import get_logger

logger = get_logger("data-service.storage")

# 检测可用引擎
_HAS_PYARROW = False
try:
    import pyarrow  # noqa: F401
    _HAS_PYARROW = True
except ImportError:
    pass


class ParquetStorage:
    """
    本地 K 线存储。

    目录结构：
        data/klines/BTCUSDT/5m/2026-09.parquet  (或 .pkl)
    """

    def __init__(self, base_dir: str | Path = "./data"):
        self.base_dir = Path(base_dir)
        self.klines_dir = self.base_dir / "klines"
        self.klines_dir.mkdir(parents=True, exist_ok=True)
        self.ext = ".parquet" if _HAS_PYARROW else ".pkl"
        logger.info(f"Storage engine: {'parquet' if _HAS_PYARROW else 'pickle'} (ext={self.ext})")

    def _month_path(self, symbol: str, interval: str, dt: datetime) -> Path:
        year_month = dt.strftime("%Y-%m")
        dir_path = self.klines_dir / symbol.upper() / interval
        dir_path.mkdir(parents=True, exist_ok=True)
        return dir_path / f"{year_month}{self.ext}"

    def _symbol_interval_dir(self, symbol: str, interval: str) -> Path:
        return self.klines_dir / symbol.upper() / interval

    def _read(self, path: Path) -> pd.DataFrame:
        if path.suffix == ".parquet":
            return pd.read_parquet(path)
        return pd.read_pickle(path)

    def _write(self, df: pd.DataFrame, path: Path) -> None:
        if path.suffix == ".parquet":
            df.to_parquet(path, index=False, compression="zstd")
        else:
            df.to_pickle(path)

    def get_latest_open_time(self, symbol: str, interval: str) -> Optional[datetime]:
        dir_path = self._symbol_interval_dir(symbol, interval)
        if not dir_path.exists():
            return None
        files = sorted(dir_path.glob(f"*{self.ext}"), reverse=True)
        if not files:
            return None
        try:
            df = self._read(files[0])
            if df.empty or "open_time" not in df.columns:
                return None
            latest = df["open_time"].max()
            if isinstance(latest, pd.Timestamp):
                return latest.to_pydatetime()
            return latest
        except Exception as e:
            logger.warning(f"Failed to read latest open_time: {e}")
            return None

    def save_klines(self, df: pd.DataFrame, symbol: str, interval: str) -> int:
        if df.empty:
            return 0

        df = df.copy()
        if "open_time" not in df.columns:
            raise ValueError("DataFrame must contain 'open_time' column")

        if not pd.api.types.is_datetime64_any_dtype(df["open_time"]):
            df["open_time"] = pd.to_datetime(df["open_time"], utc=True)
        elif df["open_time"].dt.tz is None:
            df["open_time"] = df["open_time"].dt.tz_localize("UTC")
        else:
            df["open_time"] = df["open_time"].dt.tz_convert("UTC")

        df["symbol"] = symbol.upper()
        df["interval"] = interval
        df["_year_month"] = df["open_time"].dt.strftime("%Y-%m")
        added = 0

        for year_month, group in df.groupby("_year_month"):
            group = group.drop(columns=["_year_month"])
            sample_dt = group["open_time"].iloc[0].to_pydatetime()
            path = self._month_path(symbol, interval, sample_dt)

            if path.exists():
                existing = self._read(path)
                combined = pd.concat([existing, group], ignore_index=True)
                combined = combined.drop_duplicates(subset=["open_time"], keep="last")
                combined = combined.sort_values("open_time").reset_index(drop=True)
                new_count = len(combined) - len(existing)
            else:
                combined = group.sort_values("open_time").reset_index(drop=True)
                new_count = len(combined)

            self._write(combined, path)
            added += max(new_count, 0)
            logger.debug(f"Saved {len(combined)} rows to {path} (new ≈ {new_count})")

        logger.info(f"Saved klines for {symbol} {interval}: +{added} new candles")
        return added

    def load_klines(
        self,
        symbol: str,
        interval: str,
        start: Optional[datetime] = None,
        end: Optional[datetime] = None,
    ) -> pd.DataFrame:
        dir_path = self._symbol_interval_dir(symbol, interval)
        if not dir_path.exists():
            return pd.DataFrame()

        files = sorted(dir_path.glob(f"*{self.ext}"))
        if not files:
            return pd.DataFrame()

        dfs = []
        for f in files:
            try:
                dfs.append(self._read(f))
            except Exception as e:
                logger.warning(f"Failed to read {f}: {e}")

        if not dfs:
            return pd.DataFrame()

        df = pd.concat(dfs, ignore_index=True)
        df = df.drop_duplicates(subset=["open_time"]).sort_values("open_time").reset_index(drop=True)

        if start is not None:
            if start.tzinfo is None:
                start = start.replace(tzinfo=timezone.utc)
            df = df[df["open_time"] >= pd.Timestamp(start)]
        if end is not None:
            if end.tzinfo is None:
                end = end.replace(tzinfo=timezone.utc)
            df = df[df["open_time"] <= pd.Timestamp(end)]

        return df.reset_index(drop=True)

    def list_available(self) -> list[dict]:
        result = []
        if not self.klines_dir.exists():
            return result

        for symbol_dir in self.klines_dir.iterdir():
            if not symbol_dir.is_dir():
                continue
            for interval_dir in symbol_dir.iterdir():
                if not interval_dir.is_dir():
                    continue
                files = list(interval_dir.glob(f"*{self.ext}"))
                if not files:
                    continue
                try:
                    first = self._read(sorted(files)[0])
                    last = self._read(sorted(files)[-1])
                    result.append({
                        "symbol": symbol_dir.name,
                        "interval": interval_dir.name,
                        "files": len(files),
                        "start": str(first["open_time"].min()) if not first.empty else None,
                        "end": str(last["open_time"].max()) if not last.empty else None,
                        "path": str(interval_dir),
                    })
                except Exception as e:
                    logger.warning(f"Failed to inspect {interval_dir}: {e}")
        return result
