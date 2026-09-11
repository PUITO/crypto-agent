# 固定签名（覆盖安装）

GitHub Actions 默认 `~/.android/debug.keystore` **每个 runner 不同**，会导致：

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 安装失败，无法覆盖。

本目录 `crypto-agent-upload.jks` 供 **debug 与 release 统一签名**：

| 项 | 值 |
|----|-----|
| storePassword / keyPassword | `cryptoagent` |
| alias | `cryptoagent` |
| applicationId | `com.puito.cryptoagent`（禁止 `.debug` 后缀） |

## 用户侧

1. **从旧包（系统 debug 签名或 `com.puito.cryptoagent.debug`）升级：必须先卸载一次**。
2. 之后同一 jks 打出的 APK 可直接覆盖安装，本地配置保留。
3. versionCode 由 CI 使用 `10000 + run_number`，避免降级安装失败。

## 开发

`app/build.gradle.kts` 的 `signingConfigs.stable` 指向本文件；缺失则构建失败。
