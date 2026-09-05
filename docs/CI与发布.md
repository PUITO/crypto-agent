# CI 与发布

## 工作流

| 文件 | 触发 | 作用 |
|------|------|------|
| `.github/workflows/ci.yml` | push / PR | 依赖安装、导入与 compileall |
| `.github/workflows/release.yml` | tag `v*` 或手动 | 打源码包 + GitHub Release |
| `.github/workflows/deploy.yml` | push main/master 或手动 | SSH 生产部署；**未配置 Secrets 时跳过并成功** |

## 发版示例

```bash
# 已通过 API/本地创建 tag 亦可
git tag v0.0.2
git push origin v0.0.2
```

Release 产物示例：`crypto-agent-v0.0.1-dev.tar.gz`。

## 部署 Secrets

| Secret | 必需 | 说明 |
|--------|------|------|
| `DEPLOY_HOST` | 部署时 | 服务器 |
| `DEPLOY_USER` | 部署时 | SSH 用户 |
| `DEPLOY_SSH_KEY` | 部署时 | 私钥 |
| `DEPLOY_PATH` | 可选 | 默认 `/opt/crypto-agent` |
| `PUBLIC_BASE_URL` | 可选 | 摘要里展示的公网 URL |
| `GITHUB_SYNC_REPO` | 可选 | 私有仓 `owner/name`（不含 PAT） |

## 本地 PAT 与 Actions

- 维护者推送代码/打 tag：需要 `repo`（+ 改 workflow 时 `workflow`）
- Actions 内发 Release：使用 `GITHUB_TOKEN`，无需把个人 PAT 放进 Actions Secret
- 用户恢复持久化：浏览器 PAT，与 CI 无关

## 监控

GitHub → Actions 查看每次 run；Deploy 摘要中会打印公网地址（若已配置）。
