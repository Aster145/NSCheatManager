# 协议与文件边界 / Protocol and file boundaries

## 中文

### 网络服务

所有连接均以设置中的固定 IPv4 为目标，彼此独立：

- sys-botbase TCP `6000`：连接后读取 TID、BID、Main/Heap base，并执行内存 read/write/freeze/unFreeze。命令串行化，操作提交前重新核对设备、TID、BID 和会话 generation。
- FTP TCP `21`：匿名登录、被动模式，仅同步当前已识别游戏。优先临时上传后 rename，并核对大小；服务器不支持 rename 时必须再次确认直接覆盖。
- 兼容 Noexs TCP `7331`：只发送一字节 `0x18` (`DetachDmnt`)，精确读取 4 字节小端结果。不会 attach、pause、resume 或发送其他 Noexs 命令。

这些服务通常没有互联网级身份验证。只应在可信局域网使用，并用网络隔离/防火墙阻止公网访问。

### 文件路径

本地镜像使用应用私有目录，但保留以下相对结构；FTP 使用相同的 Switch 根路径：

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/<BID>/notes.txt
```

只同步当前识别的 TID/BID。下载先暂存并解析 cheat，确认后才替换本地文件；上传不会执行金手指，也不保证 Atmosphère 自动重载。

### ZIP import and export

导出文件名为 `<TID>_<BID>.zip`，内部必须包含：

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/<BID>/notes.txt
```

缺少 cheat 会阻止导出；缺少 notes 时，只有用户确认后才加入空 `notes.txt`。

导入只允许一个游戏/Build：必需 cheat，加上可选的匹配 notes。TID 必须是 16 位十六进制，BID 必须是有效十六进制且两条路径一致。ZIP 不允许任何额外条目，也拒绝绝对路径、反斜杠/规范化歧义、`.`、`..`、符号链接、重复路径、加密条目、不支持的压缩、条目过多、单项或展开总量超限。完整校验和 cheat 解析成功后才显示预览并原子替换本地镜像。导入不会上传、执行或切换设备。

## English

The app targets only the fixed IPv4 selected in Settings. sys-botbase uses TCP `6000`; anonymous passive FTP uses TCP `21`; compatible Noexs uses TCP `7331` and sends only `DetachDmnt` byte `0x18`, followed by an exact four-byte little-endian result read. The clients and failure states are independent.

Only the current recognized game is synchronized, using:

```text
/atmosphere/contents/<TID>/cheats/<BID>.txt
/atmosphere/contents/<TID>/cheats/<BID>/notes.txt
```

ZIP export preserves the same relative paths. Import accepts exactly one required cheat and one optional matching notes file, rejects every extra or unsafe entry, fully validates before preview, and atomically replaces the app-local mirror only after confirmation. Import never uploads, executes cheats, or changes devices.

These services generally do not provide internet-grade authentication. Keep them on a trusted LAN and block public exposure.
