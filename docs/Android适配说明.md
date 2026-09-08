# Crypto-Agent × Android App 适配说明

分支：`android-app`  
配套客户端：https://github.com/PUITO/crypto-app

## 目标

- Agent 微服务为 Android 提供 **可选的云端能力**（K 线、回测、插件信号）
- App **默认可完全离线本地运行**；配置 Agent Gateway 后可走 `/api/v1/mobile/*`
- 策略 JSON Schema 与 App 端 `StrategyConfig` 对齐，便于热同步

## Mobile API（Gateway :8000）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/mobile/health` | 移动端探活 |
| GET | `/api/v1/mobile/symbols` | 币种/周期默认值 |
| GET | `/api/v1/mobile/klines?symbol=&interval=&limit=` | 对齐 App Candle 的 K 线 |
| GET | `/api/v1/mobile/strategy/schema` | 指标与规则说明 |
| POST | `/api/v1/mobile/signals` | 信号（plugin 不可用则 fallback local） |
| POST | `/api/v1/mobile/backtest` | 回测（不可用则 fallback local） |

Schema 文件：`schemas/android_strategy_config.schema.json`

## App 侧接入建议

1. 设置中增加 `Gateway Base URL`（可选）
2. 有网且 health 成功 → 优先 mobile/klines
3. signals/backtest 返回 `fallback: local` 时用本地引擎

## 自动化发布

- 推送到 `android-app` 分支 → Actions **Android Branch CI & Release**
- 产物：源码包 + 文档，Release tag `android-YYYYMMDD-<sha>`
- 同步说明触发 crypto-app 构建：见 workflow 摘要中的链接

## 本地验证

```bash
./scripts/start_dev.sh gateway data-service
curl -s http://127.0.0.1:8000/api/v1/mobile/health
curl -s "http://127.0.0.1:8000/api/v1/mobile/symbols"
```
