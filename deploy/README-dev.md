# TeamOne 后端开发指引（M0）

## 启动

```bash
cp deploy/.env.example deploy/.env   # 填入真实数据库凭据（.env 不会入库）
bash deploy/dev.sh run               # 构建并启动 :8080（Windows 也可用 deploy/dev.cmd）
```

Docker 附加组件（Valkey/MinIO/Gitea）见 `deploy/docker-compose.yml`（M1 接入，M0 非必需）。

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
