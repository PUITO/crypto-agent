# 固定签名（覆盖安装）

GitHub Actions 默认 debug 密钥每个 runner 不同，会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

本目录 `crypto-agent-upload.jks` 供 debug/release 统一签名：

- storePassword / keyPassword: `cryptoagent`
- alias: `cryptoagent`
- applicationId: `com.puito.cryptoagent`

**首次从旧版（系统 debug 签名或 `.debug` 包名）升级时需卸载一次**，之后同一签名 APK 可直接覆盖安装并保留配置。
