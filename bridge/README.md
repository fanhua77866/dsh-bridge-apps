# DSH Bridge（电脑端桥接器）

Python 常驻服务，是手机 App 访问电脑的唯一入口。提供：

- PIN 配对 + 设备 cookie 鉴权
- HTTP/WebSocket 代理到本机 DeepSeek Harness（手机遥控）
- 电脑文件浏览、下载、目录打包
- 手机 → 电脑 文件上传
- 电脑 → 手机 文件推送（收件箱 + 通知）
- 安卓 App / 语音模型版本分发（version.json + latest.apk）
- 可选 ASR 代理（DSH 录音的实时逐字校验）

## 文件

| 文件 | 作用 |
|---|---|
| dsh_phone_bridge.py | 主程序：HTTP/WS 服务器、路由、配对鉴权、代理 |
| dsh_files.py | /files 文件浏览、下载、目录打包 |
| dsh_inbox.py | /_bridge/push 推送收件箱与 /files/inbox 下载 |
| dsh_upload.py | 手机上传（/files/upload 等） |
| dsh_push.py | 命令行推送工具（agent / 脚本调用） |
| watch_bridge.py | 可选守护进程：退出后自动重启 |
| start-bridge.cmd | Windows 一键启动 |
| config.example.json | 配置模板 |
| allow-firewall.cmd | Windows 防火墙放行 8799（可选） |

## 环境要求

- Windows / Linux / macOS，Python 3.9+
- 本机已安装并运行 **DeepSeek Harness** 桌面端（配对时桥接器需要它的上游通道；也可以用 config 里的 spawn 让它自己拉起一个 phone profile 实例）
- 不依赖第三方 Python 包（仅标准库）

## 快速开始

    cd bridge
    copy config.example.json config.json
    REM 修改 workspace、upload_dir、pin
    python dsh_phone_bridge.py

启动后看到类似日志：

    HTTP listening on 0.0.0.0:8799 (LAN 192.168.1.100)

手机 App 填 http://<电脑IP>:8799 + PIN 即可配对。

## 配置（config.json）

| 字段 | 说明 |
|---|---|
| listen_host | 监听地址，局域网使用 0.0.0.0 |
| listen_port | 端口，默认 8799 |
| workspace | 工作区目录（文件浏览的默认根） |
| upload_dir | 手机上传文件保存目录 |
| pin | 6 位数字配对 PIN，请自行设置 |
| attach | 已运行的 DSH 实例地址（如 127.0.0.1:5979），留空则自动发现/spawn |
| spawn | 是否允许桥接器自己拉起 DSH phone profile 实例 |
| dsh_profile | 拉起实例使用的 profile 名，默认 phone |
| upstream_port | 自拉实例的端口，默认 15099 |
| inject_mobile | 是否向页面注入移动端样式（默认 false） |

首次启动会自动生成 bridge-state.json（设备 token 等），该文件含敏感信息，已在 .gitignore 排除。

## 接口速查

| 接口 | 鉴权 | 说明 |
|---|---|---|
| GET /_bridge/info | 无 | 状态与自动发现 |
| POST /_bridge/pair | PIN | 配对，返回设备 cookie |
| POST /_bridge/revoke | cookie | 吊销所有设备 |
| GET /app/<code>/version | 无 | App/模型版本信息 |
| GET /app/<code>/latest.apk | 无 | 下载最新 APK / 模型 zip |
| GET /files/api/list、/files/download、/files/zip | cookie | 文件浏览/下载 |
| POST /files/upload?dir=&name= | cookie | 手机上传 |
| POST /_bridge/push | 仅回环 | 电脑推送文件到手机 |
| GET /files/api/inbox、/files/inbox/<id> | cookie | 收件箱同步/下载 |
| /asr/* | cookie | 可选 ASR 服务代理 |

完整字段与示例见 ../docs/PROTOCOL.md。

## 推送示例

    python dsh_push.py "C:\work\结果.zip" --note "训练结果，点开下载"

手机 App 会在几秒内收到通知。

## 安全

- 默认 HTTP 明文，仅建议局域网/VPN 使用；不要直接暴露公网
- PIN 用 hmac.compare_digest 校验；设备 cookie 为 HttpOnly
- 推送接口限制 127.0.0.1
- 文件路径限制在允许根目录内
- 更多见 ../docs/SECURITY.md

## 常见问题

- 手机连不上：检查防火墙是否放行端口、listen_host 是否为 0.0.0.0、手机与电脑是否同一网段
- 配对返回 503：本机 DSH 上游未就绪，确认桌面端已运行或 config.attach/spawn 正确
- 推送提示 403：/_bridge/push 只允许本机调用
- 端口被占用：改 config.listen_port 后重启
