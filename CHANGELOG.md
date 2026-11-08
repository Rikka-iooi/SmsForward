# Changelog

## 1.0.0 — 2026-11-08

首个版本。

- Android 8.0–16（minSdk 26 / targetSdk 34）
- 监听 `SMS_RECEIVED`，支持长短信拼接
- 邮箱通道：QQ 邮箱 / 163 / Gmail / Outlook / 自定义 SMTP
  - SSL/TLS 与 STARTTLS
  - 授权码使用 Android Keystore AES-256-GCM 加密
- HTTPS Webhook JSON 通道
- 本地队列 + JobScheduler 重试（5 次，指数退避）
- 最近记录、失败原因、手动重试、复制正文
- Android 15/16 侧载应用“受限制的设置”权限指引
- 小米/HyperOS、华为/荣耀、OPPO/一加/realme、vivo/iQOO、三星、魅族、华硕自启动页快捷入口
- 关闭 Android 备份；不申请 `READ_SMS`
- 真机验证：真实运营商短信 → SMTP 邮箱转发成功
