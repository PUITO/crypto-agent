# Crypto Agent 微服务骨架

专注 BTC 等加密市场的历史/实时价格分析自主 Agent 系统 —— 可独立开发与测试的微服务骨架。

## 目录结构

```
crypto-agent/
├── libs/common/          # 所有服务共享的基础库（配置、日志、健康检查、异常、消息）
├── services/
│   ├── data/             # 行情采集 & 数据集（端口 8001）
│   ├── config/           # 统一配置中心（端口 8002）
│   ├── plugin/           # 策略热插拔（端口 8003）
│   ├── backtest/         # 回测 & 模拟交易（端口 8004）
│   ├── chart/            # 图表绘图指令（端口 8005）
│   ├── agent/            # LLM 大脑 + 对话（端口 8006）
│   ├── multi_agent/      # 多分身矩阵（端口 8007）
│   └── gateway/          # API 网关（端口 8000）
├── templates/            # 新服务模板
├── frontend/             # 前端占位
├── requirements.txt
├── .env.example
└── docker-compose.yml
```

## 快速开始（独立测试单个服务）

### 1. 安装依赖

```bash
cd crypto-agent
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

### 2. 启动任意服务（示例）

```bash
# Data Service
cd services/data/app
python main.py
# 访问 http://localhost:8001/docs

# Config Service
cd services/config/app
python main.py
# 访问 http://localhost:8002/docs

# 其他服务同理，端口见各 main.py 中的 Settings.port
```

每个服务都自带：
- `/health` 健康检查
- `/docs` Swagger 文档
- 统一异常处理
- CORS
- 日志

### 3. 共享库说明

所有服务通过 `sys.path` 临时引入 `libs/common`（开发期方便）。  
正式环境建议把 `libs/common` 做成可安装包，或统一设置 `PYTHONPATH=../../libs`。

### 4. 新增微服务

1. 复制 `templates/service_template` 到 `services/新服务名`
2. 修改 `Settings.service_name` 和 `port`
3. 在 `create_app()` 中挂载自己的路由
4. 直接 `python main.py` 即可独立运行测试

## 端口分配

| 服务              | 端口 |
|-------------------|------|
| Gateway           | 8000 |
| Data Service      | 8001 |
| Config Service    | 8002 |
| Plugin Service    | 8003 |
| Backtest Service  | 8004 |
| Chart Service     | 8005 |
| Agent Service     | 8006 |
| Multi-Agent       | 8007 |

## 下一步开发建议

1. 完善 **Data Service**：真实 Binance K 线拉取 + 本地 Parquet 存储 + 定时任务
2. 完善 **Config Service**：持久化 + 事件广播
3. 把现有 `btc-event-contract-signal` 封装进 **Plugin Service**
4. 在 **Backtest Service** 接入 vectorbt 或轻量回测引擎
5. **Agent Service** 接入 LangGraph + Tools（调用其他服务）
6. 前端对接 Gateway 或直接对接各服务

## 设计原则

- 每个服务可独立启动、独立测试
- 公共能力只写在 `libs/common`，禁止复制粘贴
- 业务逻辑与框架代码分离
- 早期用内存/文件即可，接口保持稳定，后期再换 Redis/DB

## Data Service（已实现）

真实 Binance K 线采集 + 本地存储 + 5 分钟定时增量 + 数据集构建 + Hugging Face 上传。

### 启动

```bash
cd services/data/app
export PYTHONPATH=../../../libs:$PYTHONPATH
python main.py
# 文档: http://localhost:8001/docs
```

### 主要接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/latest_price` | 最新成交价 |
| GET | `/api/v1/klines` | K 线（local / binance） |
| GET | `/api/v1/local/list` | 本地已存数据列表 |
| POST | `/api/v1/fetch` | 手动触发采集（支持回填） |
| GET | `/api/v1/scheduler/status` | 定时任务状态 |
| POST | `/api/v1/scheduler/run_once` | 立即跑一轮采集 |
| POST | `/api/v1/dataset/build` | 构建特征+标签数据集 |
| POST | `/api/v1/dataset/push_hf` | 构建并推送到 Hugging Face |

### 环境变量（可选）

```bash
export HF_TOKEN=hf_xxxx          # Hugging Face Token
export HF_REPO_ID=PUITO/crypto-btc-5m
export DATA_DIR=./data
export FETCH_EVERY_SECONDS=300
export SYMBOLS=BTCUSDT
```

### 使用 Hugging Face 数据集

```python
from datasets import load_dataset
ds = load_dataset("PUITO/crypto-btc-5m")  # 替换为你的 repo
df = ds["train"].to_pandas()
```

## API Gateway（统一入口 :8000）

所有后端服务可通过 Gateway 访问，前端只需配置一个 baseURL。

| 前缀 | 上游 |
|------|------|
| `/data/*` | Data :8001 |
| `/config/*` | Config :8002 |
| `/plugin/*` | Plugin :8003 |
| `/backtest/*` | Backtest :8004 |
| `/chart/*` | Chart :8005 |
| `/agent/*` | Agent :8006 |
| `/multi-agent/*` | Multi-Agent :8007 |
| `/api/v1/health/all` | 聚合健康检查 |

```bash
cd services/gateway/app
export PYTHONPATH=../../../libs:$PYTHONPATH
python main.py
# http://localhost:8000/docs
# 例: GET http://localhost:8000/data/api/v1/latest_price
# 例: POST http://localhost:8000/agent/api/v1/chat
```

## 前端（用户端最小界面）

目录：`frontend/`（Vite + React + TypeScript + Lightweight Charts）

功能：
- K 线图表（对接 Data / Chart）
- Chat 对话（对接 Agent，支持配置确认）
- 交易设置弹窗（模式 / 交易对 / 插件）
- 通过 Gateway `:8000` 统一访问后端

```bash
cd frontend
npm install
npm run dev
# http://localhost:5173
# 开发代理：/gw → http://localhost:8000
```

请先启动 Gateway 与各后端服务，否则图表与 Chat 会显示离线。
