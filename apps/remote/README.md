# DSH 遥控（Remote）

> 把电脑上的 **DeepSeek Harness** 搬到手机上：一次 PIN 配对，之后打开 App 就能全屏遥控桌面会话，并顺手取回电脑文件。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-1.6-blue)

DSH 遥控是 [DSH 桥接器及相关安卓软件](../../README.md) 的手机遥控端，与电脑端的 Python 桥接器配套使用。App 自己不实现 DSH 的后端能力，只负责配对、显示和转发；真正执行任务的是桥接器后面的 DeepSeek Harness 实例。

## 功能

### 配对与连接

- **6 位 PIN 配对**：首次填写桥接器地址 + PIN，通过 `POST /_bridge/pair` 换取 HttpOnly 设备 cookie；之后打开 App 免登录。
- **主备双地址（v1.6）**：连接页可同时填写「电脑地址」和「备用地址」（例如家里局域网 + 出门使用的 Tailscale）。主地址加载失败时自动切到备用地址，并用本机保存的 PIN 静默重新配对。
- **手动切换线路**：⋮ 菜单可随时在主 / 备地址之间切换；切换成功后两个地址对调，下次回家仍能自动切回。
- **断开配对**：⋮ 菜单 →「断开配对」，清除本机配对状态（服务端设备记录不在此操作中吊销，见「已知限制」）。

### 遥控会话

- **全屏 WebView**：加载桥接器代理的 DSH Web UI，发消息、查看流式输出、审批工具调用、切换会话都在手机上完成。
- **智能返回键**：按返回时先尝试关闭页面内的抽屉 / 浮层（`window.__dshClose`），没有可关闭项才让 WebView 后退。
- **底部浮动工具栏**：收起时只露一条把手；展开后有「刷新 / 会话 / 文件 / 更多」四个入口。
- **分享文字到 DSH**：在其他 App 里选中文字并「分享」到 DSH 遥控，会填入当前会话的输入框（纯文本；不自动发送，由你确认后提交）。
- **屏幕常亮**：⋮ 菜单可开关，默认开启，适合长时间盯输出。
- **网页内上传附件**：DSH 网页里的图片 / 文件选择按钮会调起系统选择器；「仅云端」的照片会先落到 App 缓存再交给网页，避免读取失败。

### 文件

- **内置文件页**：工具栏「📁 文件」打开桥接器的手机版文件浏览器，可浏览工作区、用户目录和盘符并下载文件。
- **系统下载**：WebView 触发的 http(s) 下载交给系统 `DownloadManager`，自动带设备 cookie 和文件名，保存到手机「Download」目录，通知栏显示进度。

### 状态与更新

- **常驻连接通知**：配对后启动前台服务，每 30 秒探测一次 `GET /_bridge/info`，通知栏显示「已连接 / 上游未就绪 / 连不上桥接器」，可一键断开。
- **App 内更新**：⋮ →「检查更新」请求 `GET /app/version`，发现新版本后下载 `/app/latest.apk` 并调起系统安装器。
- **深色渐变界面**：配对页与工具栏为深色卡片风格，尽量少遮挡 DSH 页面。

## 快速开始

1. **电脑端启动桥接器**（首次先复制配置，按需修改工作区、上传目录和 PIN）：

   ```powershell
   cd bridge
   copy config.example.json config.json
   python dsh_phone_bridge.py
   ```

   启动窗口会打印本机地址与配对 PIN，保持窗口运行。

2. **手机连接同一网络**：与电脑在同一 Wi-Fi，或处于同一个 Tailscale / ZeroTier 网络。
3. **安装并配对**：安装自行构建的 `app-debug.apk`（见下文「构建」）或已有发布版 APK，打开后填入 `http://<电脑IP>:8799` 和 PIN，点「连接」。
4. **开始使用**：连接成功直接进入 DSH 界面；展开底部把手可打开「📁 文件」，⋮ 菜单里有检查更新、切换线路、屏幕常亮等开关。

## 截图

> **截图 TODO**：本仓库暂未附带遥控端截图（文件端截图见 [apps/files/screenshots](../files/screenshots)）。
> 计划补充：配对页、遥控主界面、底部工具栏展开、双地址切换提示、连接状态通知。欢迎 PR 投稿。

## 工作原理

DSH Web 的信任边界只认 loopback：每个请求都要带进程级 token 换来的签名 cookie，`Host` / `Origin` 也必须匹配绑定地址。手机不是 loopback，直连会被拒绝，因此需要电脑端桥接器把这条通道「借」给手机：

1. 桥接器自己拉起（或附着）一个 DSH Web 实例，保管它的 token / cookie；
2. 手机首次用 PIN 换取设备 cookie，之后所有请求只带这张设备 cookie；
3. 桥接器把手机的 HTTP / WebSocket 请求转发给上游，把 `Host` / `Origin` 改写成上游的本机地址，并吞掉上游的 `Set-Cookie`；
4. 手机 App 始终不接触 DSH 自己的凭据，桥接器只下发自己的设备 cookie。

```text
┌──────────────── 手机 ────────────────┐         ┌─────────────── 电脑 ────────────────┐
│ DSH 遥控 App                         │         │ bridge/dsh_phone_bridge.py            │
│  ├─ 配对页：地址 + 6 位 PIN           │ HTTP/WS │  ├─ PIN 配对 → 签发设备 cookie        │
│  ├─ WebView：加载 DSH Web UI          │ ──────▶ │  ├─ 持有上游 token，代换上游 cookie   │
│  ├─ 前台服务：连接状态通知            │  :8799  │  ├─ 改写 Host/Origin，反向代理        │
│  └─ DownloadManager：下载电脑文件      │         │  └─ 转发 /files、/app/* 等路由        │
└──────────────────────────────────────┘         └──────────────────┬───────────────────┘
                                                                    │ 本机回环
                                                     ┌──────────────▼───────────────┐
                                                     │ DeepSeek Harness Web 实例      │
                                                     │ 桌面端已开则附着，否则拉起     │
                                                     │ phone profile（默认 15099）    │
                                                     └──────────────────────────────┘
```

**安全模型**：设备 cookie 为 HttpOnly、有效期一年；上游 token 与 DSH 凭据不下发手机；桥接器默认使用 HTTP 明文，只建议在可信局域网 / VPN 内使用，不要直接暴露公网。详见 [docs/SECURITY.md](../../docs/SECURITY.md) 和 [bridge/README.md](../../bridge/README.md)。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 连接桥接器（HTTP / WebSocket） |
| `ACCESS_NETWORK_STATE` / `WAKE_LOCK` | 清单中的网络与保活声明（当前版本未主动使用） |
| `POST_NOTIFICATIONS` | Android 13+ 显示连接状态常驻通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 配对后维持连接状态通知的前台服务 |
| `REQUEST_INSTALL_PACKAGES` | App 内更新下载 APK 后调起系统安装器 |

- 不申请存储权限：下载走系统 `DownloadManager`。
- 不申请相机、麦克风、定位、通讯录等权限。
- `android:usesCleartextTraffic="true"`：桥接器默认是 HTTP，这也是只建议内网使用的原因之一。

## 构建

环境要求：JDK 17、Android SDK platform 35 + build-tools 35、Gradle 8.9（仓库**不含** gradle wrapper）。完整步骤见 [docs/BUILD.md](../../docs/BUILD.md)。

Windows 一键构建：

```powershell
cd apps/remote
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

脚本会把工程复制到临时目录、写入 `local.properties`，执行 `assembleDebug`，并在 `apps/remote/app-debug.apk` 输出产物。

其他方式：

- 在 `apps/remote` 下自备 `local.properties`（`sdk.dir=...`），执行 `gradle assembleDebug`；
- 用 Android Studio 直接打开 `apps/remote`；
- 仓库没有 `gradlew` / `gradlew.bat`，因此不能直接 `./gradlew assembleDebug`，需要本机 Gradle 或 Android Studio。

版本号在 `apps/remote/app/build.gradle.kts` 的 `versionCode` / `versionName` 中维护（当前为 7 / 1.6）。

技术栈：Kotlin + Jetpack Compose + Android WebView；minSdk 26（Android 8.0）、targetSdk 35。

## 与桥接器的关系

DSH 遥控是桥接器的**客户端之一**，不包含桥接器代码，也不会自己启动 DSH：

| 交互 | 用途 |
|---|---|
| `GET /_bridge/info` | 连接状态探测（文件端也用它做自动发现） |
| `POST /_bridge/pair` | PIN 配对，签发设备 cookie |
| 其余路径（含 WebSocket） | 由桥接器反向代理到本机 DSH Web |
| `GET /app/version` + `/app/latest.apk` | 遥控 App 自更新（历史兼容路由，映射到 `code=remote`） |
| `/files` 及 `/files/api/*` | 「📁 文件」页浏览与下载 |

桥接器的启动、配置、日志与常见问题见 [bridge/README.md](../../bridge/README.md)；HTTP 接口细节见 [docs/PROTOCOL.md](../../docs/PROTOCOL.md)。

如果主要目的是「电脑 ↔ 手机文件互传、预览、播放、解压、上传」，完整能力在 [DSH 文件](../files/README.md)；遥控端的「📁 文件」只是同一套 `/files` 接口的轻量入口。

## 已知限制

- **必须能访问电脑**：手机与电脑需在同一局域网，或通过 Tailscale / ZeroTier / Cloudflare Tunnel 等方案互通；桥接器必须运行。
- **明文 HTTP**：默认没有 TLS，`8799` 不要直接暴露公网；需要远程访问时建议套隧道并配合 VPN / 访问控制，PIN 只是第二道门。
- **App 内「断开配对」只清本机状态**：不会调用 `POST /_bridge/revoke`。要吊销所有设备，需在电脑端调用该接口（需要设备 cookie）。
- **本机保存最近一次 PIN**：为支持切线路时静默重配，PIN 保存在 App 私有 `SharedPreferences`；共用设备时请注意。
- **通知只有连接状态**：暂不推送「回答完成」提醒；需要看结果请打开 App。
- **仅支持手动 PIN**：暂未做二维码配对。
- **分享仅支持纯文本**：不能从其他 App 直接分享文件到 DSH 遥控；文件请用 DSH 文件 App 发送。
- **云端照片**：网页上传时「仅云端」的照片需先下载到手机，App 会给出提示。
- **窄屏布局**：DSH 自身三栏布局在小屏上偏挤，建议横屏或较大屏设备。
- **自更新依赖桥接器发布目录**：`bridge/app/remote/{version.json,latest.apk}` 存在新版本才会提示；自编译安装包需与已装版本同签名才能覆盖升级。
- **尚无截图**：见「截图」章节 TODO。

## 常见问题

| 现象 | 处理 |
|---|---|
| 连接页提示连不上桥接器 | 确认手机与电脑同网、桥接器窗口仍在运行、电脑防火墙放行 8799；可用 `Test-NetConnection <电脑IP> -Port 8799` 检查 |
| 配对返回 503 / 「上游未就绪」 | 电脑端 DSH Web 还在启动或已退出；等待片刻，或查看桥接器日志中的上游状态 |
| 页面白屏或反复重连 | 上游进程可能重启中；先刷新页面，仍不行则重启桥接器（App 无需重新配对） |
| 切到备用地址失败 | 备用地址当前不可达，或 PIN 已变更；在 ⋮ 菜单选择「断开配对」，再重新填写两个地址并配对 |
| 点「检查更新」提示已是最新版 | 桥接器发布目录 `bridge/app/remote/` 里没有更高的 `versionCode`，见 [docs/BUILD.md](../../docs/BUILD.md) |
| 下载没有动静 | App 只接管 http(s) 下载；若链接是 `content:` / `file:` 等协议，会尝试交给系统处理 |

## License

[MIT](../../LICENSE)。App 使用 AndroidX / Jetpack Compose 等第三方组件，遵循其各自许可证。
