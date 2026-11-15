@echo off
chcp 65001 >nul
title 放行 DSH 遥控桥接器端口
echo 需要管理员权限把 8799 端口放行给局域网 / 手机。
echo 会弹一次 UAC，点「是」即可。

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
    exit /b
)

netsh advfirewall firewall delete rule name="DSH Phone Bridge" >nul 2>&1
netsh advfirewall firewall add rule name="DSH Phone Bridge" dir=in action=allow protocol=TCP localport=8799 profile=any
if %errorlevel% equ 0 (
    echo.
    echo 已放行 TCP 8799。手机现在应该能连上了。
) else (
    echo.
    echo 放行失败，请手动检查防火墙。
)
echo.
pause >nul
