# TeamOne 后端开发指引（M0）

## 启动

```bash
cp deploy/.env.example deploy/.env   # 填入真实数据库凭据（.env 不会入库）
bash deploy/dev.sh run               # 构建并启动 :8080（Windows 也可用 deploy/dev.cmd）
```

Docker 附加组件（Valkey/MinIO）见 `deploy/docker-compose.yml`（W1 接入）。Git 内核为自研 bare repo（`deploy/git-init-repo.sh`），CI 为自研质量门（`deploy/ci.sh`）——D-2026-09-11-B 起不再依赖 Gitea/act_runner。

## 开发种子账号（首次启动自动创建）

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| admin | Admin@123 | OWNER | 全量权限 |
| dev1 | Dev@12345 | MEMBER 无授权 | 验证默认拒绝（403） |
| dev2 | Dev@12345 | MEMBER + ACL(user:list) | 验证 ACL 授予路径 |

## 验收（M0）

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@123"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -s localhost:8080/api/v1/users -H "Authorization: Bearer $TOKEN"         # 200
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/users             # 401
# dev1 登录后访问 /users -> 403（默认拒绝）；dev2 -> 200（ACL 授予）
```

> 数据库凭据只存 `deploy/.env`（gitignored）；任何代码/文档/配置模板中都不得出现真实凭据。

## MinIO 文件两步制（INC-2）

```bash
docker compose -f deploy/docker-compose.yml up -d minio   # host 网络直绑 9000/9001（-p 断裂教训）
bash deploy/minio-init.sh                                  # mc 建桶（读 .env MINIO_BUCKET，缺省 teamone-dev）
```

后端经 `MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BUCKET`（.env.example 有例）连接；流程：`POST /api/v1/files/presign`（≤50MB、mime 白名单、扩展黑名单）→ 客户端 PUT uploadUrl → `POST /api/v1/files/{id}/complete`（statObject 兜底）→ status=ready（消息附件只认 ready）。

## nginx TLS+WSS（INC-2 T-6，V-13 前置）

```bash
bash deploy/nginx/gen-dev-cert.sh                 # 自签证书 → deploy/nginx/tls/（gitignored）
docker compose -f deploy/docker-compose.yml up -d nginx
curl -sk https://localhost/actuator/health        # TLS 反代验证（自签须 -k）
```

验收路径：`https://localhost/api/v1/auth/login`（POST）与 `wss://localhost/ws`（自签证书客户端须忽略校验——curl `-k` / 测试客户端 `rejectUnauthorized:false` 等价）。`TEAMONE_BACKEND_ORIGIN` **禁 localhost**（host 网络容器内 localhost=WSL 自身），填 Windows 宿主 LAN IP（`.env` 可配，模板 envsubst 不硬编码）。8080 直连路径不受影响（现开发流保留）。

## IM 参数（INC-2）

- `TEAMONE_IM_WITHDRAW_WINDOW_HOURS`（=teamone.im.withdraw-window-hours，缺省 24）：消息撤回时间窗（小时）；**测试撤旧消息可配 0**（≤0=不限窗）。
- 未读真相恒为 `GET /conversations` 的 SQL 差值；Valkey `unread:{uid}` 仅缓存。每日对账脚本：`deploy/unread-recalibrate.sql`（M3 挂 pg_cron）。
