# 故障排查 / Troubleshooting

## 中文

### 无法连接或一直未就绪

1. 确认手机与 Switch 在同一可信局域网，配置的是固定 IPv4，不是主机名。
2. 分别确认 sys-botbase `6000`、FTP `21`、Noexs `7331`；一个服务失败不代表其他服务可用。
3. 检查路由器访客隔离、AP isolation、防火墙和端口冲突。不要为解决问题而把端口转发到公网。
4. sys-botbase 连接后必须有前台游戏，且 TID/BID/base 响应格式必须兼容。

### 找不到金手指

路径必须为 `atmosphere/contents/<TID>/cheats/<BID>.txt`，大小写/字符必须与识别结果一致。游戏更新通常会改变 BID，需要对应的新文件。`notes.txt` 应位于 `<BID>/notes.txt`，不是 `<BID>s/notes.txt`。

### ZIP 被拒绝

解压查看是否只有匹配的 cheat 和可选 notes。删除 macOS 元数据、顶层包装目录和其他额外文件；不要使用反斜杠、绝对路径、`.` 或 `..`。确认文件未加密、TID 为 16 位十六进制、BID 在两个路径完全相同，并且 cheat 能解析。

### FTP 上传要求再次确认

服务器不支持临时文件 rename 时，应用会要求确认直接覆盖。这比原子 rename 风险更高；先备份远端文件并保持网络稳定。上传完成不保证 Atmosphère 立即重载。

### 内存操作或锁定失败

重新识别当前游戏以刷新 Main/Heap base。检查类型、十六进制地址、完整跨度和 4 KiB 单次限制。切换设备/游戏会使旧确认失效。正常断开会尝试释放本应用锁；异常断线会显示待清理地址，只在重连同一设备及同一 TID/BID 后协调清理。不要用本应用解除其他工具创建的锁。

### Detach dmnt 失败

确认兼容 Noexs sysmodule 正在 `7331` 监听。此按钮只发送 `0x18`，不会先 attach；不兼容的 sysmodule 或协议版本会失败，但不会改变 sys-botbase 连接状态。

### 构建问题

使用 JDK 17 和仓库 Wrapper：`./gradlew --version`。安装 Android SDK 36 并配置 `local.properties` 或 `ANDROID_HOME`。`local.properties`、密钥库和签名 secrets 不得提交。

## English

Use a fixed IPv4 on the same trusted LAN and verify ports `6000`, `21`, and `7331` independently. A foreground game is required for recognition. A missing cheat usually means the recognized BID does not match `atmosphere/contents/<TID>/cheats/<BID>.txt`.

ZIPs must contain only the exact matching cheat and optional `<BID>/notes.txt`. FTP direct-overwrite confirmation means rename is unavailable; back up first. Re-recognize before Main/Heap memory operations. Session changes invalidate old confirmations, and pending lock cleanup is reconciled only for the matching device and game.

For builds, use JDK 17, Android SDK 36, and the checked-in Gradle Wrapper.
