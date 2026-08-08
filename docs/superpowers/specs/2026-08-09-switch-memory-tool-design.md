# Switch Memory Tool for Android — Design Specification

## 1. Purpose and scope

Switch Memory Tool is a native Android client for a user-owned, authorized Nintendo Switch running compatible CFW services. The first release connects to the current foreground game, displays its identifiers and memory bases, provides typed memory reads and writes, executes a safe subset of Atmosphère cheat codes once, synchronizes cheat files over anonymous FTP, and can request dmnt detachment through a compatible Noexs sysmodule.

The application does not provide public cheat downloads, online-game automation, account or ban bypass, cheat-group background execution, or arbitrary periodic memory writes. The manual memory tool may explicitly lock individual addresses through sys-botbase `freeze`/`unFreeze` until the user unlocks them.

## 2. Platform and technology

- Android 8.0 (API 26) or later.
- Kotlin, Jetpack Compose, and Material 3.
- One Android application module with package-level layering.
- Coroutines and StateFlow for asynchronous work and UI state.
- Room for devices, cheat indexes, notes metadata, and execution history.
- DataStore for the selected device and lightweight preferences.
- Android Storage Access Framework for imports and Android Sharesheet for exports.
- Localized Android resources for Simplified Chinese and English, selectable in app settings.

## 3. External services

Each saved Switch device profile contains a display name, IPv4 address, and editable ports with these defaults:

| Service | Default port | Purpose |
| --- | ---: | --- |
| sys-botbase | 6000 | Game identity, memory bases, reads, and writes |
| Anonymous FTP | 21 | Downloading and uploading cheat and notes files |
| Compatible Noexs | 7331 | Sending `DetachDmnt` (`0x18`) |

The three protocols use independent clients and connection state. FTP uses passive mode and anonymous login. Noexs support assumes the Switch has a compatible Noexs sysmodule installed.

## 4. Architecture

### 4.1 Protocol layer

`SysBotbaseClient` owns the TCP socket and exposes typed operations for `getTitleID`, `getBuildID`, `getMainNsoBase`, `getHeapBase`, `peek`, `peekMain`, `peekAbsolute`, `poke`, `pokeMain`, `pokeAbsolute`, and pointer operations where appropriate. Commands are serialized through one mutex-protected queue so responses cannot be associated with the wrong request.

`SwitchFtpClient` performs constrained file operations below `/atmosphere/contents`. It supports existence checks, directory creation, download to a temporary file, temporary upload, rename, and size verification.

`NoexsClient` implements the compatible Noexs command framing and sends only `DetachDmnt` (`0x18`) for this feature. The command must not be emitted as an unframed raw byte. It does not automatically attach, pause, resume, or disconnect another debugger.

### 4.2 Cheat layer

- `CheatFileParser` parses UTF-8 Atmosphère text files, `[cheat name]` groups, comments, blank lines, hexadecimal words, and source line numbers.
- `CheatValidator` validates the complete selected group before any write: format, Title ID and Build ID association, supported opcode forms, widths, ranges, instruction count, and total I/O limits.
- `CheatInterpreter` maintains sixteen unsigned 64-bit temporary registers and converts supported instructions into reads, integer operations, and writes.
- `CheatExecutor` executes the resulting operations sequentially. It stops on the first failure and reports whether nothing or only part of the group executed.

### 4.3 Data and domain layers

The local mirror retains the Atmosphère relative layout:

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/notes.txt
```

The application owns the physical mirror root to avoid storage-permission differences across Android releases. Imported files are copied into that mirror. Room stores indexes and state rather than the primary text content.

Domain use cases cover device management, connection, automatic foreground-game recognition, manual memory access and locking, cheat import and lookup, one-shot cheat execution, FTP synchronization, editing, ZIP export, sharing, and dmnt detachment. UI code never sends protocol commands or interprets opcodes directly.

## 5. Device and session behavior

Users can add, edit, delete, and select multiple device profiles from a top-level drop-down. The last selected profile is restored at launch.

The main device row contains the profile drop-down followed by two actions: a connection toggle labelled **Connect** or **Disconnect**, and a separate **Detach dmnt** button. A successful sys-botbase connection automatically performs foreground-game recognition once, retrieving TID, BID, main base, and heap base and loading the matching local cheat file. Manual recognition and BID/TID refresh are one operation named **Recognize current game again**.

Switching devices closes the old sockets but does not clear game information or cheat check states. Each device separately retains its last recognized TID, BID, bases, loaded file, and checked groups. On reconnection, the app automatically refreshes identity and bases:

- If TID and BID still match, bases are refreshed and check state remains.
- If TID or BID changed, the old state remains as history, the UI marks the game as changed, and the matching file for the new game is loaded.
- Cached bases are display-only until revalidated.
- Memory actions are disabled until identity validation completes.
- Reconnection, device switching, and identity refresh never replay a checked cheat.

## 6. Screens and interactions

### 6.1 Cheat-first main screen

The default landing screen is cheat-first. It contains the selected device row, connection status, compact current-game identity, and matching cheat groups. Recognition runs automatically after connection and looks up:

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
```

When found, the corresponding cheat groups load automatically. Otherwise, the app reports that no cheat file exists for the current version and offers import or FTP download.

The top-right overflow menu contains:

- A checkable **Edit mode** item. Checked means edit mode; unchecked means view mode.
- **Recognize current game again**, combining recognition and TID/BID refresh as one manual fallback.
- **Download from Switch** and **Upload to Switch** for the current BID cheat and `notes.txt`.
- **Package and share ZIP**.
- **Settings**.
- **About**.

The **Detach dmnt** action remains beside the IP and connection toggle rather than inside the menu. Memory and file actions require the relevant service and a validated game identity.

### 6.2 Cheats screen

The screen displays the current TID, BID, mirror path, and cheat groups. It defaults to view mode. View/edit switching is controlled only by the checkable overflow-menu item, not a persistent segmented control.

- Checking a group immediately validates and executes it once.
- Successful groups remain checked and show their last execution time.
- Unchecking changes UI state only and never restores memory.
- A transition from unchecked to checked is the only UI event that triggers execution.
- Incompatible groups show the unsupported opcode and source line and cannot be checked.
- During execution the affected group cannot be tapped repeatedly.

Edit mode exposes tabs for the current `<BID>.txt` and `notes.txt`. It allows changes to group names, code, and notes. Saving reparses and validates the whole cheat file, writes through a temporary file, updates the index, unchecks **Edit mode**, and returns to view mode. Cancelling or leaving with unsaved changes requires confirmation and also returns to view mode. Saving a syntactically valid file may retain groups that use unsupported instructions, but those groups remain visibly non-executable.

### 6.3 Memory screen

The memory tool supports absolute, main-relative, and heap-relative addresses. Values support hexadecimal bytes, signed or unsigned integer widths of 8/16/32/64 bits as applicable to the UI control, Float, and Double. Encoding is always little-endian. Reads and writes are separate operations; manual writes show an address, byte count, and value confirmation. A single read is limited to 4 KiB by default.

A checkable **Lock** control appears after **Read** and **Write**. Checking it resolves the current address to an absolute address and invokes sys-botbase `freeze`; unchecking invokes `unFreeze`. Address mode, address, type, and value controls are disabled while locked. Locks are tracked per device. A normal disconnect first releases locks created by this app; after an abnormal disconnect they are shown as pending cleanup and reconciled after reconnection. Cheat groups remain one-shot and never use locking implicitly.

### 6.4 Settings and library

Settings manages multiple default device profiles, their aliases and IP addresses, per-device ports, connection and command timeouts, imported cheat files, and local mirror entries. One profile is selected as the default. It also provides an immediate interface-language selector for **简体中文** and **English**. Notes use a dedicated editor for `notes.txt` and never become cheat instructions.

### 6.5 About

The **About** item in the top-right overflow menu opens a dedicated screen showing the application name, icon, semantic version, purpose, open-source credits, license information, and a concise risk disclaimer. The first release displays version `1.0.0`; implementation must read the displayed value from Android build metadata rather than duplicating a hard-coded UI string.

Credits identify sys-botbase for remote control and memory access, Atmosphère for CFW and cheat-format documentation, and the compatible Noexs project for the dmnt detach protocol. The screen states that the app is for user-owned or authorized devices and that CFW, remote debugging, memory modification, and incompatible codes may cause crashes, data loss, or bans.

The screen includes **Join QQ group** with visible group number `457965140`. Activating it launches the system handler for this HTTPS group-sharing URL, with a browser fallback when QQ is unavailable:

```text
https://qun.qq.com/universal-share/share?ac=1&authKey=fPqdvU2BW8s731iMkSW6OnVdc2ArUNe0ocLG%2FrbpMsEwJ4Ke1k7ksAmlkPkkMioj&busi_data=eyJncm91cENvZGUiOiI0NTc5NjUxNDAiLCJ0b2tlbiI6IkRRL1VKeG5BNmViMm9iRVVLTlUwYzVGK29nMG1IZGEyRWI0STh1TkszQ0NkeTdlTEtINTdqRUl3ZzJobGNNV0MiLCJ1aW4iOiIxNDUxMTc5NDgxIn0%3D&data=DbCHiE8dRZyXk6WkCg8btr6oOQrPK5vR_rCm0YXC5MrwseWitvCVjXfMvfh-qFBFJXSpAUVuzhIDT59CYoyWEA&svctype=4&tempid=h5_group_info
```

## 7. Supported Atmosphère cheat subset

The first release uses a strict allowlist. A selected group executes only if every instruction is recognized and supported.

- `0x0`: static writes of 1, 2, 4, or 8 bytes to supported main, heap, or absolute addressing forms.
- `0x4`: load a 64-bit constant into a temporary register.
- `0x5`: load a register from supported main/heap relative memory or dereference a register address, covering common `580F0000` and `580F1000` pointer chains.
- `0x6`: write a 1, 2, 4, or 8-byte static value to a supported register-derived address, covering common `640F0000` writes.
- `0x7`: legacy integer addition and subtraction, including common `780F0000` offsets.
- `0x9`: integer add, subtract, shifts, AND, OR, XOR, and move. Floating-point operations are excluded.
- `0xA`: write a register value through supported register, fixed-offset, main-relative, or heap-relative address forms.

The release excludes conditionals, else blocks, loops, key triggers, saved/static registers, debug logging, Alias/ASLR regions, floating-point register arithmetic, cheat-group freezing, and periodic cheat execution. Unsupported forms produce a source-located validation error and are never silently ignored. Manual address locking is a separate memory-tool feature and does not expand the cheat interpreter.

## 8. Execution safeguards

Before execution, the app rechecks the device, TID/BID association, current validated bases, complete group validation, arithmetic overflow, address range representation, instruction-count limit, and aggregate I/O limit. Pointer dereferences use explicit reads and reject invalid or null results.

Cheat execution is not transactional. If a connection fails after earlier writes succeeded, those writes cannot be rolled back. The app stops immediately, reports partial execution, preserves source and command context, and never automatically retries or replays a memory write.

## 9. FTP synchronization

Synchronization is limited to the currently recognized game and these two remote files:

```text
/atmosphere/contents/<TID>/cheats/<BID>.txt
/atmosphere/contents/<TID>/cheats/notes.txt
```

Download stages each file in temporary local storage. The cheat file must parse successfully before replacing the local mirror; a missing remote file is reported and does not create an empty file. Existing local content requires overwrite confirmation.

Upload requires matching local files and creates missing remote directories. It prefers upload-to-temporary-name followed by rename, verifies the resulting remote size, and asks before replacing an existing file. Where practical, the existing remote file is downloaded as a local backup first. If rename is unavailable, the user must explicitly approve direct replacement. Upload does not execute cheats or guarantee that Atmosphère reloads them immediately.

## 10. ZIP packaging, sharing, and notes

For the recognized TID/BID, the app creates `<TID>_<BID>.zip` with the preserved relative paths:

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
atmosphere/contents/<TID>/cheats/notes.txt
```

The archive is written to app-controlled cache, exposed through a `FileProvider`, and shared using the Android Sharesheet with temporary read permission. Missing `notes.txt` is represented by an empty notes file only after user confirmation; a missing cheat file blocks packaging.

`notes.txt` is plain UTF-8 text. It has independent view and edit modes and is never parsed as cheat code. It participates in local storage, FTP synchronization, ZIP packaging, and sharing.

## 11. Connection and error model

Each protocol exposes explicit disconnected, connecting, connected, busy, and failed states. Commands have separate connection and operation timeouts. One protocol failing does not invalidate the others, but its dependent UI actions become unavailable.

Errors are categorized as connection, game recognition, file/path, parse/validation, memory execution, FTP transfer, archive/share, and Noexs protocol errors. User messages are concise Chinese descriptions; details retain the operation, source line where relevant, and exception category. Logs stay on-device, are size-limited, and do not include unrelated network traffic.

## 12. Testing strategy

- Parser tests cover groups, comments, blank lines, casing, encodings, malformed hex, and precise line numbers.
- Interpreter tests cover every accepted opcode form, pointer chains, little-endian conversion, register behavior, overflow, and every rejected class.
- Fake sys-botbase server tests cover fragmented responses, serialization, timeouts, disconnects, automatic recognition, reads, writes, freeze/unFreeze, lock cleanup, and partial execution.
- Fake passive FTP server tests cover missing directories, downloads, staged uploads, rename fallback, overwrite confirmation, notes, and size verification.
- Fake Noexs server tests assert correct framing and command value `0x18`, timeouts, repeat taps, and device targeting.
- Repository tests cover per-device state isolation, TID/BID lookup, mirror replacement, notes, and migrations.
- UI tests cover connect-triggered recognition, manual re-recognition, no-match behavior, check-to-execute, uncheck-without-rollback, menu-controlled edit/view modes, IP-row actions, device switching, language switching, ZIP sharing, manual lock/unlock, and disabled unsafe actions.
- About-screen tests cover the build-derived version, localized explanatory text, credit and risk sections, visible QQ group number, valid HTTPS intent, and browser fallback.
- Real-device acceptance covers Android 8.0, a current Android release, and an authorized CFW Switch with the required services.

## 13. Acceptance criteria

The release is accepted when it can:

1. Save and switch among multiple Switch profiles without losing per-device display or check state.
2. Retrieve current TID, BID, main base, and heap base through sys-botbase.
3. Read and confirm-write common little-endian value types in three address modes.
4. Match the current game to the local Atmosphère mirror.
5. Parse complete cheat groups and execute only the documented subset once on check.
6. Reject unsupported or malformed groups before the first write.
7. Download and upload the current BID cheat and `notes.txt` over anonymous FTP.
8. View and edit cheat and notes files without corrupting the last valid copy.
9. Package both files with Atmosphère paths and share the ZIP through Android.
10. Send framed Noexs `DetachDmnt` command `0x18` without attaching or pausing.
11. Avoid automatic replay after disconnect, device switch, identity refresh, upload, or UI recomposition.
12. Automatically recognize the current game after a successful connection and expose one combined manual re-recognition action.
13. Switch the complete interface between Simplified Chinese and English.
14. Freeze and unfreeze a manually selected address while preventing accidental edits to its locked parameters.
15. Display an About screen with the build-derived version, usage notes, credits, risk warning, and a working QQ group link for group `457965140`.
