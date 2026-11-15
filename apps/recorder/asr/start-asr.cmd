@echo off
chcp 65001 >nul
title DSH 录音 · 电脑识别服务（faster-whisper）
cd /d "%~dp0"
set "PY=%~dp0.venv\Scripts\python.exe"
if not exist "%PY%" (
  echo 没找到虚拟环境：%PY%
  echo 请先运行：python -m venv .venv 然后 pip install faster-whisper
  pause
  exit /b 1
)
echo 启动识别服务（第一次加载模型约 10~30 秒，模型 small / CPU int8）...
echo 识别服务端口 8801；桥接器会自动拉起，也可以单独开这个窗口看日志。
"%PY%" dsh_asr.py
echo.
echo 识别服务已退出，按任意键关闭窗口。
pause >nul
