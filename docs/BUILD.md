# 构建指南

## 环境要求

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | 所有安卓 App |
| Android SDK | platform 35、build-tools 35.0.0 | 所有安卓 App |
| Gradle | 8.9 | 构建（仓库不含 wrapper，可用本机 Gradle 或 Android Studio） |
| Python | 3.9+ | 桥接器、DSH 录音的 ASR 服务 |
| DeepSeek Harness | 最新版 | 桥接器代理的目标 |

Android Studio 打开 apps/<app>/ 也能直接构建（会自动生成 local.properties）。

## 通用构建（命令行）

每个 App 目录下都有 build-apk.ps1（Windows）：

    set JAVA_HOME=C:\Program Files\Java\jdk-17
    set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    set GRADLE_HOME=C:\gradle-8.9
    cd apps\files
    powershell -ExecutionPolicy Bypass -File build-apk.ps1

脚本会把工程复制到临时目录、写入 local.properties、执行 assembleDebug，并在当前目录输出 app-debug.apk。

Linux/macOS：

    cd apps/files
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    gradle assembleDebug

## 各 App 特殊依赖

### DSH 遥控 / DSH 文件 / DSH 语料

无额外依赖，标准安卓构建即可。

### DSH 语音（apps/voice）

- 依赖 sherpa-onnx AAR：把 sherpa-onnx-<version>.aar 放到 app/libs/（仓库不包含，见该目录 README）
- 模型不随仓库分发：把训练好的模型放到 app/src/main/assets/models/<模型名>/（model.onnx、tokens.txt、lexicon.txt），详见该目录 PLACE_MODELS_HERE.md
- 模型导出方式：VITS 微调后运行 sherpa-onnx 的 export 脚本（见 apps/voice/README.md）

### DSH 录音（apps/recorder）

- 安卓端可直接构建
- 实时 ASR 需要电脑端：python -m venv asr/.venv 并安装 faster-whisper，把 Whisper small 模型放到 asr/models/small
- 启动：asr/start-asr.cmd（或由桥接器收到 /asr 请求时自动拉起）

## 桥接器

    cd bridge
    copy config.example.json config.json
    REM 编辑 config.json：workspace=你的工作区、upload_dir=手机上传目录、pin=6位数字
    python dsh_phone_bridge.py

可选：把 DeepSeek Harness 桌面端路径/端口填到 attach，或保持 spawn=true 让桥接器自己拉起 phone profile 实例。

## 发布 App 更新（可选）

构建完成后把 APK 放成：

    bridge/app/<code>/latest.apk
    bridge/app/<code>/version.json

version.json 字段：

    {
      "versionCode": 12,
      "versionName": "1.11",
      "size": 9061448,
      "sha256": "<sha256>",
      "publishedAt": "2026-11-13T12:00:00+08:00"
    }

手机 App 会在下次检查更新时发现新版本。模型更新同理（latest.apk 放模型 zip）。

## 常见问题

- 手机上打不开电脑：确认同一 Wi-Fi、电脑防火墙允许 8799、config 里 listen_host 是 0.0.0.0
- 配对提示 PIN 错误：PIN 是 config.json 里的 pin 字段，不是锁屏密码
- App 构建报 SDK 找不到：local.properties 的 sdk.dir 必须指向 Android SDK 根目录
- DSH 语音提示无模型：确认 assets/models/<名称>/ 三个文件齐全，模型必须带非空 onnx metadata comment（sherpa-onnx 要求）
