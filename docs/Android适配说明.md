# Crypto-Agent Android 适配说明

分支：`android-app`  
**独立客户端**，不依赖、不引用 [crypto-app](https://github.com/PUITO/crypto-app)。

## 源码

`android/` — Kotlin + Jetpack Compose，对接 Gateway。

## API

| 用途 | 路径 |
|------|------|
| 健康 | `GET /api/v1/health/all` |
| Mobile | `GET /api/v1/mobile/*` |
| K 线 | `GET /api/v1/mobile/klines` 或 `/data/api/v1/klines` |
| 对话 | `POST /agent/api/v1/chat` |
| 配置 | `GET /config/api/v1/config` |

## 发布

- Tag：`android-app-latest`
- 工作流：`Build Android App APK`
