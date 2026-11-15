# 架构说明

## 组件

| 组件 | 形态 | 职责 |
|---|---|---|
| 桥接器 bridge/ | Python 常驻进程（默认 :8799） | 手机的唯一入口：PIN 配对、设备 cookie 鉴权、HTTP/WS 代理到本机 DSH、文件浏览/下载/打包、手机上传、推送收件箱、App/模型版本分发、ASR 代理（可选） |
| DeepSeek Harness | 桌面程序 | 真正的 AI 会话宿主；桥接器既可以附着在已运行的实例上，也可以自己拉一个 phone profile 实例 |
| DSH 遥控 | 安卓 App | WebView 全屏显示手机版 DSH 页面；常驻通知、分享文字到 DSH |
| DSH 文件 | 安卓 App | 文件浏览/下载/上传/预览/解压/播放/分享；接收电脑推送通知 |
| DSH 语音 | 安卓 App | 完全离线 TTS（sherpa-onnx VITS），模型可热更新；音频保存到音乐库并提供内置播放器 |
| DSH 语料 | 安卓 App | 按脚本逐句录制训练语音，本地质检，zip 导出 / 一键上传 |
| DSH 录音 | 安卓 App | 较早的逐句录制工具，支持电脑端实时 ASR 逐字校验 |

## 部署形态

### 形态 A：局域网（默认）

    手机 ──Wi-Fi──▶ 电脑:8799 桥接器 ──127.0.0.1──▶ DSH 桌面端

适合在家使用。PIN 只在首次配对时输入一次，之后手机凭服务端签发的 cookie 访问。

### 形态 B：Tailscale / VPN 异地

手机和电脑都加入同一个 Tailscale 网络后，手机访问电脑的 100.x.x.x 地址。
桥接器代码不区分网络类型，但请注意：不要在公网直接暴露 8799 端口（见 SECURITY.md）。

## 典型数据流

### 1. 手机遥控 DSH

    App ──GET /──────▶ 桥接器 ──转发 HTTP/WS──▶ DSH 桌面端
        └─ 设备 cookie 鉴权（首次 POST /_bridge/pair 用 PIN 换 cookie）

### 2. 电脑推送文件到手机

    agent/脚本 ──POST /_bridge/push（仅回环）──▶ 桥接器收件箱
    手机 App ──轮询 GET /files/api/inbox──▶ 发现新条目 → 弹通知 → GET /files/inbox/<id> 下载

### 3. 手机上传文件到电脑

    手机 App ──POST /files/upload?dir=&name=（raw body，设备 cookie）──▶ 桥接器写入 upload_dir

### 4. App / 模型自更新

    App ──GET /app/<code>/version──▶ 比较 versionCode
        └─ 有新版本：GET /app/<code>/latest.apk → 系统安装器（普通 App）
        └─ DSH 语音的模型更新同理：latest.apk 实际是模型 zip，App 内解压导入

### 5. 语音合成与训练闭环

    DSH 语料：录 708 句 → 质检 → 上传 zip（wav + metadata.txt）
    PC 训练：清洗/切分 → VITS 微调 → 导出 sherpa-onnx（model.onnx/tokens.txt/lexicon.txt）
    发布：bridge/app/voicemodel/latest.apk（模型 zip）+ version.json
    DSH 语音：检查更新 → 下载模型 → 本地离线合成 → 音频保存到手机音乐库

### 6. 实时 ASR（DSH 录音，可选）

    手机 ──每 0.5s 送 PCM──▶ 桥接器 /asr/* ──▶ 本机 faster-whisper 服务（:8801）
        └─ 返回已确认字数与逐字时间轴 → App 高亮读对/读错

## 关键设计取舍

- 桥接器不做业务逻辑，只做「鉴权 + 代理 + 文件/分发」；App 与桌面端解耦
- 所有 App 内更新走统一的 version.json + latest.apk 约定，不依赖应用商店
- 语音相关的模型/语料体积大且含隐私，默认不随仓库分发，需要用户自行准备
- 手机端不跑 ASR/TTS 训练，重计算全部留在电脑
