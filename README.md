# Crypto Agent

专注 **BTC 等加密市场** 的历史/实时价格分析与自主 Agent 系统。  
微服务架构：行情采集、统一配置、策略热插拔、回测、图表、LLM 对话、多 Agent 优化、运维、日志、持久化同步、通知。

## 文档导航

| 文档 | 说明 |
|------|------|
| [快速开始](docs/快速开始.md) | 本地安装、一键启动、验证 |
| [架构说明](docs/架构说明.md) | 微服务职责、数据流、端口 |
| [微服务手册](docs/微服务手册.md) | 各服务 API 与职责摘要 |
| [开发与调试](docs/开发与调试.md) | 脚本、VS Code、单服务调试 |
| [配置与安全](docs/配置与安全.md) | Config 分栏、PAT Cookie、密钥测试 |
| [生产部署指南](docs/生产部署指南.md) | 私有仓、Actions、公网访问 |
| [CI 与发布](docs/CI与发布.md) | CI / Release / Deploy |
| [完整架构与前端设计流程图](完整架构与前端设计流程图.md) | Mermaid 流程图（历史设计稿） |

方案与选型（根目录历史文档，可作背景阅读）：
- `加密市场自主Agent系统_方案与实施计划.md`（若存在）
- `技术架构与技术选型说明.md`（若存在）

## 30 秒启动

```bash
git clone https://github.com/PUITO/crypto-agent.git
cd crypto-agent
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
./scripts/start_dev.sh
./scripts/status.sh
```

- Gateway / API 文档：http://127.0.0.1:8000/docs  
- 健康聚合：http://127.0.0.1:8000/api/v1/health/all  

前端：

```bash
cd frontend && npm install && npm run dev
# 或静态演示：python3 -m http.server 5500 --directory frontend
```

## 核心能力

- **对话驱动**：切换指标/模式，图表绘制（斐波那契、压力位等）
- **自主回测 / 模拟**：按配置参数回测，优化矩阵
- **热插拔策略**：Plugin 服务加载训练后的策略文件
- **统一配置**：Config 微服务 + 前端分栏；变更可提示重启并一键 Ops 重启
- **集中日志**：Log Service；业务服务通过 `common.logging` 自动上报
- **持久化同步**：`data/persist` → 私有 GitHub 仓；**PAT 仅浏览器 Cookie**
- **通知**：Webhook / Telegram / EmailJS

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Gateway | 8000 | 统一入口与代理 |
| Data | 8001 | K 线 / 数据集 |
| Config | 8002 | 配置中心 |
| Plugin | 8003 | 策略插件 |
| Backtest | 8004 | 回测 |
| Chart | 8005 | 绘图对象 |
| Agent | 8006 | LLM + Chat |
| Multi-Agent | 8007 | 参数矩阵 |
| Ops | 8008 | 生命周期 / 日志入口 |
| Log | 8009 | 集中日志 |
| Sync | 8010 | GitHub 持久化同步 |
| Notify | 8011 | 通知 |

## 技术栈

- 后端：Python 3.12、FastAPI、uvicorn、httpx、pandas  
- 前端：React + Vite、Lightweight Charts  
- 运维：Shell 脚本、GitHub Actions、可选 Docker Compose  

## 许可证

以仓库内声明为准；默认仅作研究与自用部署参考。
