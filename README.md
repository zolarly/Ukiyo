# Ukiyo 浮世

原图跨机互传，24 小时后不留痕迹。

朋友用邀请码上传照片，得到一个链接；收到链接的人免登录下载原图（字节不变，EXIF 与动态照片内嵌视频都保留）。24 小时后链接失效、文件删除。

## 目录

- `server/` Go 后端，仅用标准库，单个约 9MB 的静态二进制，无数据库
- `app/` Kotlin 原生安卓客户端（待开发）
- `docs/requirements.md` 需求文档

## 本地运行

```sh
cd server
UKIYO_INVITE_CODES=abc123 go run .
# 打开 http://localhost:8080 上传
go test ./...
```

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `UKIYO_INVITE_CODES` | 必填 | 允许上传的邀请码，逗号分隔 |
| `UKIYO_ADDR` | `:8080` | 监听地址 |
| `UKIYO_DATA` | `data` | 文件存放目录 |
| `UKIYO_TTL` | `24h` | 保留时长 |
| `UKIYO_MAX_MB` | `300` | 单次上传上限 |

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
After=network.target

[Service]
WorkingDirectory=/opt/ukiyo
Environment=UKIYO_INVITE_CODES=换成你的邀请码
Environment=UKIYO_ADDR=127.0.0.1:8080
ExecStart=/opt/ukiyo/ukiyo
Restart=always
DynamicUser=yes
StateDirectory=ukiyo
Environment=UKIYO_DATA=/var/lib/ukiyo

[Install]
WantedBy=multi-user.target
```

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

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/shares` | `Authorization: Bearer <邀请码>`，multipart 字段 `file`（可多个）→ `{token, url, expires_at}` |
| `GET` | `/api/shares/{token}` | `{files: [{name, type, size, url}], expires_at}` |
| `GET` | `/s/{token}` | 网页查看/下载 |
| `GET` | `/s/{token}/{i}` | 原文件，支持 Range 断点续传；`?inline` 在页面内预览 |
