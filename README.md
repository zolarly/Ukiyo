# Ukiyo 浮世

原图跨机互传，24 小时后不留痕迹。

发送方在 App 里注册登录后上传照片，得到一个链接，可以限制查看次数（1、3、5 次或不限）；收到链接的人免登录下载原图（字节不变，EXIF 与动态照片内嵌视频都保留）。24 小时后或次数用完后链接失效、文件删除。

## 目录

- `server/` Go 后端，单个静态二进制（纯 Go SQLite，无需 cgo），另需一个 Redis
- `app/` Kotlin 原生安卓客户端（待开发）
- `docs/requirements.md` 需求文档

## 本地运行

```sh
docker run -d --name ukiyo-redis -p 6379:6379 redis:7
cd server
cp config.example.json config.json   # 填写 jwt_secret
go run .                              # 默认读取 ./config.json，可以用 -config 指定路径
go test ./...                         # 测试用 miniredis，不需要真 Redis
```

| 配置项 | 默认 | 说明 |
|---|---|---|
| `jwt_secret` | 必填 | 签发登录令牌的密钥，至少 32 个字符 |
| `addr` | `:8080` | 监听地址 |
| `data_dir` | `data` | 文件和 `ukiyo.db`（SQLite）存放目录 |
| `ttl` | `24h` | 保留时长 |
| `max_upload_mb` | `300` | 单次上传上限 |
| `max_total_mb` | `20000` | data 目录总量上限，超过后拒绝上传（507） |
| `daily_upload_mb` | `2048` | 每个账号每天的上传额度，超过后拒绝上传（429） |
| `max_views_options` | `[0, 1, 3, 5]` | 上传时允许的查看次数，`0` 表示不限 |
| `redis_addr` | `127.0.0.1:6379` | Redis 地址，启动时连不上直接退出 |
| `android_package` | 空 | App 包名，用于 App Links |
| `android_cert_sha256` | 空 | App 签名证书的 SHA-256 指纹，用于 App Links |

## 部署（Linux + systemd + Caddy）

```sh
# 本机交叉编译
cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o ukiyo .
scp ukiyo root@服务器:/opt/ukiyo/
```

`/etc/systemd/system/ukiyo.service`：

```ini
[Unit]
Description=Ukiyo
After=network.target redis-server.service

[Service]
WorkingDirectory=/opt/ukiyo
ExecStart=/opt/ukiyo/ukiyo -config /etc/ukiyo/config.json
Restart=always
DynamicUser=yes
StateDirectory=ukiyo

[Install]
WantedBy=multi-user.target
```

先装好 Redis（`apt install redis-server`，默认只监听本机）。`/etc/ukiyo/config.json` 中把 `addr` 设为 `127.0.0.1:8080`，`data_dir` 设为 `/var/lib/ukiyo`；文件属主设为 `root`、权限设为 `644`，因为 `DynamicUser` 每次启动都是随机用户，需要能读取（文件里有 `jwt_secret`，服务器上不要有其他不可信用户）。

限流按 `X-Forwarded-For` 的最后一段识别 IP，所以后端必须在 Caddy 后面，不能直接暴露。

`/etc/caddy/Caddyfile`（自动 HTTPS，域名需已备案）：

```
你的域名 {
	request_body {
		max_size 300MB
	}
	reverse_proxy 127.0.0.1:8080
}
```

```sh
systemctl enable --now ukiyo && systemctl reload caddy
```

## API

标「登录」的接口要带 `Authorization: Bearer <access_token>`，令牌无效或过期返回 401。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/register` | `{username, password}` → `{access_token, refresh_token, username}`。用户名 3–20 位字母、数字、下划线（不区分大小写），密码至少 8 位，不合格 400；已存在 409；同 IP 每小时超过 5 个 429 |
| `POST` | `/api/auth/login` | 同上返回；用户名或密码错误 401；同 IP 15 分钟内失败 10 次后 429 |
| `POST` | `/api/auth/refresh` | `{refresh_token}` → 新的一对令牌，旧的立即作废；无效 401 |
| `POST` | `/api/auth/logout` | `{refresh_token}` → 204 |
| `POST` | `/api/shares` | 登录。multipart：`max_views`（可选，默认 `0` 不限）+ `file`（可多个）→ `{token, url, expires_at, max_views}`。依次检查：401 → 507 总量 → 429 每日额度 → 413 单次大小；`max_views` 不在可选项里 400 |
| `GET` | `/api/shares/{token}` | `{files: [{name, type, size}], expires_at, max_views, views_left}`，不消耗次数，不含下载地址；不限次数时 `views_left` 为 `null`；不存在 404，次数用完 410 |
| `POST` | `/api/shares/{token}/open` | 消耗一次 → `{ticket, files: [{name, type, size, url}]}`，`url` 带 `?ticket=`，3 小时有效；次数用完 410 |
| `GET` | `/api/me/shares` | 登录。自己未过期的分享 `[{token, url, count, expires_at, max_views, views_used}]`，新的在前 |
| `DELETE` | `/api/shares/{token}` | 登录。本人 204；别人的 403；不存在 404 |
| `GET` | `/s/{token}` | 网页：文件列表和「查看」按钮，不消耗次数 |
| `POST` | `/s/{token}/open` | 网页「查看」：消耗一次，跳转到 `/s/{token}?ticket=…` 显示预览和下载（刷新不再消耗） |
| `GET` | `/s/{token}/{i}?ticket=…` | 原文件，支持 Range 断点续传；`&inline` 在页面内预览；票据无效 403 |
| `GET` | `/.well-known/assetlinks.json` | App Links 验证（配置了 `android_*` 才有） |

用掉最后一次查看后，分享最多再保留 3 小时（票据有效期），供这次下载和续传，之后删除。
