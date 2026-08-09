# NSCheatManager（NS金手指管理）

NSCheatManager 是一个 Android 原生应用，用于连接用户拥有或获授权的 CFW Nintendo Switch，识别当前游戏、管理 Atmosphère 金手指与备注、执行受支持的单次代码，以及进行手动内存读写和锁定。

> [!WARNING]
> 本项目会远程修改 Switch 内存。错误地址、不兼容代码、CFW 或网络中断可能造成游戏崩溃、存档或其他数据损坏，联网使用还可能带来账号或主机封禁风险。仅在自有或获授权设备上使用；先备份重要数据。金手指执行不是事务，已经完成的写入无法回滚。

本项目是非官方社区项目，与 Nintendo、Atmosphère、sys-botbase 和 Noexs 的作者或维护者没有隶属、赞助或认可关系。

## 功能

- 保存并切换多个固定 IPv4 设备配置；连接后自动识别 TID、BID、Main Base 和 Heap Base。
- 从应用私有本地镜像载入 `[金手指名称]` 分组；勾选后只执行一次，取消勾选只清除界面/持久化状态。
- 导入、编辑、保存并分享当前游戏的金手指和独立 `notes.txt`。
- 通过匿名被动 FTP 仅同步当前 TID/BID 的两个文件。
- 按绝对、Main 相对、Heap 相对地址读写常用小端类型；写入前确认；可锁定并精确解锁由本应用创建的地址。
- 通过兼容 Noexs sysmodule 只发送 `DetachDmnt` (`0x18`)。
- 简体中文与 English 界面；Android 8.0（API 26）及以上。

## Switch 前置条件与端口

Switch 必须位于可信局域网并使用固定 IPv4：

| 服务 | 默认端口 | 要求 |
|---|---:|---|
| [sys-botbase](https://github.com/olliz0r/sys-botbase) | `6000` | 游戏识别、内存读写、freeze/unFreeze |
| 匿名 FTP sysmodule/server | `21` | 匿名登录、被动模式、可访问 `/atmosphere/contents` |
| 兼容 Noexs sysmodule | `7331` | 支持 `DetachDmnt` (`0x18`) 及 4 字节小端结果 |

应用不会安装或更新 Switch 端组件。请按各上游项目说明安装与确认兼容性，不要把这些端口暴露到互联网。

## 安装与配置

1. 从受信任的 Release 下载 APK，并核对同名 `.sha256` 文件。
2. 在 Android 8.0 或更高版本允许从所用文件管理器/浏览器安装未知应用，然后安装 APK。
3. 打开“设置”，添加 Switch 的固定 IPv4；端口默认 `6000`、`21`、`7331`。
4. 选择设备并连接。连接成功后会自动识别当前前台游戏并查找本地镜像。
5. 首次使用写入、锁定、上传或导入前，请阅读确认信息并备份数据。

更详细的协议与故障排查见 [协议说明](docs/PROTOCOLS.md) 和 [故障排查](docs/TROUBLESHOOTING.md)。

## 本地文件、FTP 与 ZIP

应用私有镜像和 Switch FTP 都保持 Atmosphère 相对结构：

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/<BID>/notes.txt
```

TID 必须为 16 位十六进制；两个路径中的 BID 必须完全相同。`notes.txt` 是 UTF-8 纯文本，不会作为金手指执行。

导入 ZIP 必须只包含必需的 `<BID>.txt`，以及可选的匹配 `<BID>/notes.txt`。额外文件、绝对路径、反斜杠歧义、`.`/`..`、符号链接、重复规范化路径、加密/不支持压缩、过多或过大的条目都会使整个导入失败。完整规则见 [协议说明](docs/PROTOCOLS.md#zip-import-and-export)。

## 支持的金手指子集

第一版采用严格 allowlist：`0x0`、`0x4`、`0x5`、`0x6`、`0x7`、`0x9`、`0xA` 的特定形式。选中分组会先完整验证，任何不支持的指令都会在首次写入前拒绝。精确形式、宽度、寻址模式和限制见 [金手指兼容性](docs/CHEAT-COMPATIBILITY.md)。

不支持条件/else、循环、按键触发、saved/static registers、调试日志、Alias/ASLR 区域、浮点寄存器运算、金手指分组冻结或周期执行。手动“锁定”是独立内存工具功能。

## 从源码构建

要求：JDK 17、Android SDK（compile/target SDK 36）以及可联网解析 Gradle 依赖。仓库包含 Gradle Wrapper 9.3.1。

```powershell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本地 `assembleRelease` 在未配置签名时会生成 unsigned APK，仅用于验证构建，不应作为公开安装包发布。

发布候选的签名、版本核对与校验和流程见 [贡献指南](CONTRIBUTING.md)。

## 截图

仓库当前没有已确认的产品截图，因此不放置占位图或虚构界面。cheat-first、编辑、内存、设置与关于页面的真实设备截图将在后续 v1.0.0 真机验收完成后加入。

## 社区、许可与致谢

- QQ 群：`457965140` — [加入群聊](https://qun.qq.com/universal-share/share?ac=1&authKey=fPqdvU2BW8s731iMkSW6OnVdc2ArUNe0ocLG%2FrbpMsEwJ4Ke1k7ksAmlkPkkMioj&busi_data=eyJncm91cENvZGUiOiI0NTc5NjUxNDAiLCJ0b2tlbiI6IkRRL1VKeG5BNmViMm9iRVVLTlUwYzVGK29nMG1IZGEyRWI0STh1TkszQ0NkeTdlTEtINTdqRUl3ZzJobGNNV0MiLCJ1aW4iOiIxNDUxMTc5NDgxIn0%3D&data=DbCHiE8dRZyXk6WkCg8btr6oOQrPK5vR_rCm0YXC5MrwseWitvCVjXfMvfh-qFBFJXSpAUVuzhIDT59CYoyWEA&svctype=4&tempid=h5_group_info)
- 本项目以 [GNU GPL v3 only](LICENSE) 发布。
- [sys-botbase](https://github.com/olliz0r/sys-botbase) 提供远程控制/内存协议参考，采用 GPL-3.0。
- [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere) 提供 CFW 与 [cheat 格式文档](https://github.com/Atmosphere-NX/Atmosphere/blob/master/docs/features/cheats.md)，其仓库采用 GPL-2.0，并包含项目注明的例外。
- [PointerSearcher-SE](https://github.com/tomvita/PointerSearcher-SE)（MIT）是兼容 Noexs `DetachDmnt` 交互的参考来源。

各上游名称、代码和文档仍受各自许可证约束；本仓库的 GPL-3.0-only 声明不会替代上游许可证。

---

# NSCheatManager — English

NSCheatManager is a native Android app for an owned or authorized CFW Nintendo Switch. It recognizes the foreground game, manages Atmosphère cheats and notes, executes the supported one-shot subset, and provides confirmed manual memory access.

> [!WARNING]
> This app remotely modifies Switch memory. Wrong addresses, incompatible codes, CFW faults, or network interruption can crash software, damage saves or other data, and may create account or console ban risk when used online. Back up important data. Cheat execution is not transactional and completed writes cannot be rolled back.

This is an unofficial community project. It is not affiliated with, sponsored by, or endorsed by Nintendo, Atmosphère, sys-botbase, or Noexs contributors.

## Requirements and setup

- Android 8.0 / API 26 or newer.
- A fixed Switch IPv4 on a trusted LAN.
- sys-botbase on TCP `6000`.
- An anonymous passive FTP service on TCP `21`, with access to `/atmosphere/contents`.
- A compatible Noexs sysmodule on TCP `7331` for the optional one-byte `DetachDmnt` (`0x18`) command.

Install a trusted APK after verifying its `.sha256`, add a device in Settings, then connect. Recognition runs once automatically and obtains TID, BID, Main Base, and Heap Base. Do not expose any of these unauthenticated services to the internet.

## Files and compatibility

The app-local mirror and FTP preserve exactly:

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/<BID>/notes.txt
```

ZIP import accepts only the required cheat and optional matching notes entry. See [Protocols](docs/PROTOCOLS.md), [Cheat compatibility](docs/CHEAT-COMPATIBILITY.md), and [Troubleshooting](docs/TROUBLESHOOTING.md).

Supported opcode families are the approved forms of `0x0`, `0x4`, `0x5`, `0x6`, `0x7`, `0x9`, and `0xA`. The whole group is validated before the first write. Unchecking never restores memory, and checked groups are never replayed automatically.

## Build

Install JDK 17 and Android SDK 36, then run:

```powershell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. An unsigned local release build is validation-only and must not be publicly distributed as a signed release.

## Screenshots

No verified product screenshots exist in the repository yet, so no placeholders are presented. Real-device screenshots will be added during the later v1.0.0 acceptance task.

## Community, license, and credits

- QQ group `457965140`: [join link](https://qun.qq.com/universal-share/share?ac=1&authKey=fPqdvU2BW8s731iMkSW6OnVdc2ArUNe0ocLG%2FrbpMsEwJ4Ke1k7ksAmlkPkkMioj&busi_data=eyJncm91cENvZGUiOiI0NTc5NjUxNDAiLCJ0b2tlbiI6IkRRL1VKeG5BNmViMm9iRVVLTlUwYzVGK29nMG1IZGEyRWI0STh1TkszQ0NkeTdlTEtINTdqRUl3ZzJobGNNV0MiLCJ1aW4iOiIxNDUxMTc5NDgxIn0%3D&data=DbCHiE8dRZyXk6WkCg8btr6oOQrPK5vR_rCm0YXC5MrwseWitvCVjXfMvfh-qFBFJXSpAUVuzhIDT59CYoyWEA&svctype=4&tempid=h5_group_info)
- NSCheatManager is licensed under [GNU GPL v3 only](LICENSE).
- [sys-botbase](https://github.com/olliz0r/sys-botbase) (GPL-3.0) is credited for the remote-control and memory protocol.
- [Atmosphère](https://github.com/Atmosphere-NX/Atmosphere) (GPL-2.0 with its documented exemptions) is credited for CFW and cheat-format documentation.
- [PointerSearcher-SE](https://github.com/tomvita/PointerSearcher-SE) (MIT) is credited as the compatible Noexs protocol reference.

Upstream names, code, and documentation remain governed by their own licenses.
