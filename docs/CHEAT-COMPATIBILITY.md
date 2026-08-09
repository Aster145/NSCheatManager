# Atmosphère 金手指兼容性 / Cheat compatibility

## 中文

解析器接受 UTF-8 文本、`[名称]` 分组、空行、注释和每个 8 位十六进制 instruction word。执行器只接受下表形式；整个分组会在第一次 I/O 前验证。所有宽度仅为 `1/2/4/8` 字节，数值与内存固定为 Switch 小端序。

| Code | 已支持的精确范围 |
|---|---|
| `0x0` | 静态常量写入；Main (`M=0`)、Heap (`M=1`) 或 Absolute (`M=4`) 加编码的 offset register `R` 和 40-bit immediate offset；保留 nibble 必须为 0；8 字节使用 4 words，其余 3 words。 |
| `0x4` | `400R0000`，3 words，把 64 位常量载入临时寄存器 `R`。 |
| `0x5` | 仅 8 字节、2 words。支持 Main/Heap 固定偏移、同寄存器解引用、独立 base register 解引用，以及 Main/Heap + offset register；对应常见 `580F0000` / `580F1000` 链。Alias/ASLR 不支持。 |
| `0x6` | 3 words，从常量写入寄存器派生地址；可选 offset register 和写后按宽度递增 base register；保留位必须为 0。 |
| `0x7` | 2 words 的 legacy 整数 add/sub；目标与左操作数为同一寄存器，即时数必须适配宽度。 |
| `0x9` | 整数 add、sub、shift-left、shift-right、AND、OR、XOR、move；支持 register 或 immediate operand forms。Move 忽略右操作数并按所选宽度收窄；shift 数必须小于位宽。浮点运算不支持。 |
| `0xA` | 把 source register 的低宽度值写入：address register、register + register、register + 36-bit fixed offset、Main/Heap + address register、Main/Heap + fixed offset、Main/Heap + register + fixed offset；可选写后递增 address register。 |

执行前还会检查当前设备/TID/BID/generation、有效 base、完整地址跨度与溢出、空指针、最多 4096 条指令以及分组总 I/O 最多 16384 字节。失败后立即停止；如果先前写入已成功，它们不会回滚，也不会自动重试。

明确不支持：`0x1/0x2/0x3/0x8` 条件、else、循环、按键；`0xB`、`0xC*` saved/static register 和扩展控制；debug logging；Alias/ASLR；浮点寄存器算术；自动/周期执行；cheat group freeze。未知或不支持形式保留准确行号/opcode 诊断，不会静默忽略。

官方格式参考：[Atmosphère cheats.md](https://github.com/Atmosphere-NX/Atmosphere/blob/master/docs/features/cheats.md)。本项目支持的是严格子集，不代表完整 Atmosphère VM。

## English

UTF-8 groups and eight-hex-digit words are parsed, but only the exact allowlisted forms above execute. Widths are 1, 2, 4, or 8 bytes and all values are Switch little-endian. Families `0x0`, `0x4`, `0x5`, `0x6`, `0x7`, `0x9`, and `0xA` are supported only with the listed word counts, regions, reserved bits, operand forms, and overflow rules.

Conditions, else, loops, key triggers, saved/static registers, debug logging, Alias/ASLR, floating-point register arithmetic, periodic execution, and cheat-group freezing are excluded. A whole group is validated before its first I/O. Execution stops at the first failure; earlier writes are not transactional and cannot be rolled back.
