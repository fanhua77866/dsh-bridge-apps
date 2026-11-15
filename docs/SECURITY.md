# 安全说明

## 安全模型

| 机制 | 说明 |
|---|---|
| PIN 配对 | 手机首次连接需输入 config.json 中设置的 6 位数字 PIN |
| 设备 cookie | 配对成功后服务端签发 HttpOnly cookie（一年有效），之后免 PIN |
| 吊销 | POST /_bridge/revoke 可一次性吊销所有设备 |
| 推送接口限制 | POST /_bridge/push 只接受 127.0.0.1 调用 |
| 路径限制 | 文件浏览/上传限制在 config 配置的允许根目录内 |
| 局域网 | 默认只建议在可信局域网或 VPN 内使用 |

## 风险与建议

1. **不要直接把 8799 端口映射到公网**。桥接器默认 HTTP 明文，公网暴露会导致 PIN 被暴力破解、cookie 被窃听。
2. 异地使用请走 Tailscale/WireGuard 等 VPN，或自行在反向代理层加 HTTPS + 访问控制。
3. PIN 建议使用随机 6 位数字，不要用生日/手机号；泄露后立即改 config.json 并 revoke。
4. 桥接器运行账号的权限即 App 可访问的文件范围，建议用普通用户运行，不要用管理员。
5. 手机丢失：删除电脑 bridge-state.json 中的设备记录（或调用 revoke）即可让旧 cookie 失效。

## 报告安全问题

请通过仓库 Issue 或私下联系维护者，不要在公开 Issue 中贴出可利用细节或真实内网信息。
