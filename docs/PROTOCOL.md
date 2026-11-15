# 桥接器 HTTP 协议

桥接器默认监听 0.0.0.0:8799（可用 config.json 修改）。
除特别说明外，手机端请求都需要携带配对后获得的设备 cookie：

    Cookie: dsh_phone=<token>

## 1. 配对与设备

### GET /_bridge/info

无需鉴权，用于「自动发现」与状态检查：

    {
      "ok": true,
      "version": "1.0.0",
      "upstream": true,
      "paired": false,
      "devices": 1,
      "pinRequired": true,
      "lan": "192.168.1.100",
      "port": 8799
    }

### POST /_bridge/pair

请求：{"pin": "000000", "name": "可选设备名"}

- 200：{"ok": true, "device": "<device_id>"}，并返回 Set-Cookie: dsh_phone=<token>; Path=/; Max-Age=31536000; HttpOnly
- 403：PIN 不正确
- 503：电脑端 DSH 上游未就绪

### POST /_bridge/revoke

需要设备 cookie。吊销所有设备（返回 {"ok": true, "revoked": N}），手机需重新配对。

## 2. App / 模型版本分发

约定目录：bridge/app/<code>/{version.json, latest.apk}

### GET /app/<code>/version

返回 version.json 内容，并附带 ok、url 字段。示例：

    {
      "versionCode": 12,
      "versionName": "1.11",
      "size": 9061448,
      "sha256": "<hex>",
      "publishedAt": "2026-11-13T12:00:00+08:00",
      "ok": true,
      "url": "http://<host>/app/files/version"
    }

### GET /app/<code>/latest.apk

下载最新 APK（或模型 zip），支持 Range/HEAD。App 端比较 versionCode 决定是否下载安装。

历史兼容路由：/app/version、/app/latest.apk 映射到 code=remote。

## 3. 文件下载与浏览

| 接口 | 说明 |
|---|---|
| GET /files?path=<dir> | 浏览器友好的目录页面（需 cookie） |
| GET /files/api/roots | 可浏览的根目录列表（JSON） |
| GET /files/api/list?path=<dir> | 目录内容 JSON |
| GET /files/api/recent | 最近修改的文件 |
| GET /files/download?path=<file>&t=<token> | 下载文件，支持 Range/HEAD |
| GET /files/zip?path=<dir>&t=<token> | 目录打包 zip 下载 |

## 4. 手机 → 电脑 上传

| 接口 | 说明 |
|---|---|
| GET /files/api/upload-info | {"ok":true,"defaultDir":"...","roots":[...],"maxMb":4096} |
| POST /files/upload?dir=<abs>&name=<file> | raw body 为文件内容（必须带 Content-Length），写入目标目录 |
| GET /files/api/uploads?limit=20 | 最近上传的文件列表 |

要求设备 cookie；dir 需要在允许的根目录范围内。

## 5. 电脑 → 手机 推送（仅回环）

### POST /_bridge/push

只允许 127.0.0.1 调用（agent 本机使用）。

    {"paths": ["C:\\work\\结果.zip", "C:\\work\\图片.png"], "note": "给用户看的说明"}

目录会自动打包成 zip。返回：

    {"ok": true, "items": [{"id":"...","name":"结果.zip","size":123,"note":"..."}]}

### GET /files/api/inbox?since=<ts>

设备 cookie。返回收件箱条目（保留最近 100 条）：

    {"ok":true,"items":[{"id":"...","name":"...","size":123,"mtime":1700000000,"note":"...","source":"path"}]}

### GET /files/inbox/<id>

下载某条推送，支持 Range/HEAD。

## 6. ASR 代理（可选，DSH 录音用）

    /asr/*  → 转发到本机 faster-whisper 服务（默认 127.0.0.1:8801）

未安装/未启动时返回 503；桥接器可以在收到第一个请求时自动拉起 ASR 服务。

## 7. 代理与鉴权

- 除 /_bridge/*、/app/*、/asr/*、文件相关接口外，其余路径（含 WebSocket）都代理到本机 DSH 桌面端
- 代理前会校验设备 cookie；未配对返回 401 或配对页
- Host/Origin 会被重写，避免上游拒绝
- 详见桥接器源码 dsh_phone_bridge.py 顶部的路由注释
