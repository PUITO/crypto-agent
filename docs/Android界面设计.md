# Crypto-Agent Android 客户端界面设计

> **独立应用**：本客户端属于 `crypto-agent` 的 `android-app` 分支，**与仓库 PUITO/crypto-app 无任何代码/产品关系**。

## 定位

手机端访问 **master 微服务架构** 的 Gateway：

- 行情 K 线（data / mobile API）
- Agent 对话
- 微服务健康
- Gateway / 交易对 / 周期等连接配置

## 底部导航

| Tab | 功能 |
|-----|------|
| 行情 | 币种、周期、K 线（坐标轴 + 缩放平移） |
| 对话 | Agent Chat |
| 健康 | `/api/v1/health/all` + mobile health |
| 设置 | Gateway URL、默认交易对/周期、远端 config 预览 |

## 主色

背景 `#0B0E11`，主色 `#F0B90B`，涨 `#0ECB81`，跌 `#F6465D`。

## 包名

`com.puito.cryptoagent`（debug：`.debug`）

## 自动化

推送 `android-app` 分支且变更 `android/**` → 构建 APK → Release `android-app-latest`。
