# 贡献指南

## 提交前

1. 代码可构建：在 apps/<app> 下执行 gradle assembleDebug 通过
2. 不提交：APK、模型、录音、bridge-state.json、真实 IP/PIN/个人姓名
3. 新 App 遵循 docs/NAMING.md 的命名规范
4. 文档同步更新（功能、权限、数据格式）

## 代码风格

- Kotlin：4 空格缩进，Compose 单 Activity 风格
- Python：3.9+ 兼容，标准库优先（桥接器不引入第三方依赖）
- 提交信息：<类型>: <简短说明>（如 feat: add corpus batch export）

## 目录约定

    apps/<codelower>/   安卓源码（可直接用 Android Studio 打开）
    bridge/             桥接器 Python 源码
    docs/               文档

## 测试建议

- 桥接器：python -m py_compile bridge/*.py；本地起服务后用 curl 走一遍配对/上传/推送
- 安卓：至少在一台真机上验证「配对 → 主要功能 → App 内更新」链路
