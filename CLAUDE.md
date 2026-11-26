# Ukiyo 浮世

原图跨机互传、24 小时自动销毁的分享工具。需求见 `docs/requirements.md`，部署和 API 见 `README.md`。

## 结构

- `server/`：Go 后端。账号和分享元数据存 SQLite，查看次数、票据、续期令牌和限流计数存 Redis，原文件按分享存在 data 目录下的文件夹里。
- `app/`：Kotlin 原生安卓 App（待开发）。只做安卓，不做 iOS。

## 与用户协作

- 全程用中文交流。
- 用户在借这个项目**学习 Kotlin**，有 Java 和 Python 背景（推测，未确认）。写 Kotlin 时：
  - 遇到 Kotlin 特有的写法（空安全、扩展函数、data class、协程、Compose 等），在对话里和 Java 对比着讲。讲解放在对话里，不要写成大段代码注释。
  - 一次推进一小步，每步都能在真机上看到效果，让用户能跟上，也能自己动手。
- 只有用户要求时才提交 git。

## 约定

- Go：安装在 `C:\Program Files\Go\bin`。测试用 `cd server && go test ./...`（Redis 用 miniredis 模拟，测试不需要真 Redis）。本地运行需要 Redis：`docker run -d --name ukiyo-redis -p 6379:6379 redis:7`。第三方依赖只限 `modernc.org/sqlite`、`github.com/redis/go-redis`、`github.com/golang-jwt/jwt`，以及测试用的 `github.com/alicebob/miniredis`；再加别的需要有充分理由。
- 安卓：技术选型和依赖白名单以 `docs/requirements.md` 第 7 节为准。minSdk 29，不依赖 Google Play 服务。
- 核心原则：原图字节不能被改动，接收方不留副本。每个功能都按这两条验收。
