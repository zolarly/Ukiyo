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
cp config.example.json config.json   # 修改邀请码
go run .                              # 默认读取 ./config.json，可以用 -config 指定路径
# 打开 http://localhost:8080 上传
go test ./...
```

| 配置项 | 默认 | 说明 |
|---|---|---|
| `invite_codes` | 必填 | 允许上传的邀请码列表 |
| `addr` | `:8080` | 监听地址 |
| `data_dir` | `data` | 文件存放目录 |
| `ttl` | `24h` | 保留时长 |
| `max_upload_mb` | `300` | 单次上传上限 |
| `max_total_mb` | `20000` | data 目录总量上限，超过后拒绝上传（507） |
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
After=network.target

[Service]
WorkingDirectory=/opt/ukiyo
ExecStart=/opt/ukiyo/ukiyo -config /etc/ukiyo/config.json
Restart=always
DynamicUser=yes
StateDirectory=ukiyo

[Install]
WantedBy=multi-user.target
```

`/etc/ukiyo/config.json` 中把 `addr` 设为 `127.0.0.1:8080`，`data_dir` 设为 `/var/lib/ukiyo`；文件权限设为 `644`，因为 `DynamicUser` 每次启动都是随机用户，需要能读取。

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
| `POST` | `/api/shares` | `Authorization: Bearer <邀请码>`，multipart 字段 `file`（可多个）→ `{token, url, expires_at, delete_key}`；总量超限返回 507 |
| `GET` | `/api/shares/{token}` | `{files: [{name, type, size, url}], expires_at}` |
| `DELETE` | `/api/shares/{token}` | `Authorization: Bearer <delete_key>` → 204；key 错误 403；不存在 404 |
| `GET` | `/s/{token}` | 网页查看/下载 |
| `GET` | `/s/{token}/{i}` | 原文件，支持 Range 断点续传；`?inline` 在页面内预览 |
| `GET` | `/.well-known/assetlinks.json` | App Links 验证（配置了 `android_*` 才有） |
