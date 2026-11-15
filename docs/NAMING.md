# 命名规范

> 为了让多 App 资产一眼分清：中文显示名 + ASCII 代号（Code）+ 版本号。
> 路径、包名、文件名、路由一律 ASCII。

## App 命名

| 项 | 规则 | 示例 |
|---|---|---|
| 手机桌面显示名 | DSH <功能词>，功能词全局唯一 | DSH 遥控、DSH 文件、DSH 语音 |
| 英文代号 Code | PascalCase，全局唯一 | Remote、Files、Voice、Corpus |
| 包名 applicationId | dev.dsh.<codelower>，发布后不可改 | dev.dsh.files |
| APK 文件名 | DSH-<Code>-v<版本>.apk | DSH-Files-v1.11.apk |
| 源码目录 | apps/<codelower>/ | apps/files/ |
| 更新发布目录 | bridge/app/<codelower>/{version.json,latest.apk} | bridge/app/files/ |
| 更新路由 | /app/<codelower>/version、/app/<codelower>/latest.apk | /app/files/version |
| 版本号 | 主.次，加功能升次版本；versionCode 每次+1 | 1.10 → 1.11 |

## 当前资产清单

| 显示名 | Code | 包名 | 版本 |
|---|---|---|---|
| DSH 遥控 | Remote | dev.dsh.phone | 1.6 |
| DSH 文件 | Files | dev.dsh.files | 1.11 |
| DSH 语音 | Voice | dev.dsh.voice | 7.0 |
| DSH 语料 | Corpus | dev.dsh.corpus | 1.0 |
| DSH 录音 | Recorder | dev.dsh.recorder | 2.3 |

## 其他约定

- 桥接器端口默认 8799，不要在 App 里写死真实内网 IP
- PIN 只出现在本机 config.json，不提交仓库
- 训练/语音模型不进入 Git（见 .gitignore）
