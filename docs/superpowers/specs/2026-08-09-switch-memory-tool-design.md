# Switch Memory Tool for Android — Design Specification

## 1. Purpose and scope

Switch Memory Tool is a native Android client for a user-owned, authorized Nintendo Switch running compatible CFW services. The first release connects to the current foreground game, displays its identifiers and memory bases, provides typed memory reads and writes, executes a safe subset of Atmosphère cheat codes once, synchronizes cheat files over anonymous FTP, and can request dmnt detachment through a compatible Noexs sysmodule.

The application does not provide public cheat downloads, online-game automation, account or ban bypass, background freezing, or periodic memory writes.

## 2. Platform and technology

- Android 8.0 (API 26) or later.
- Kotlin, Jetpack Compose, and Material 3.
- One Android application module with package-level layering.
- Coroutines and StateFlow for asynchronous work and UI state.
- Room for devices, cheat indexes, notes metadata, and execution history.
- DataStore for the selected device and lightweight preferences.
- Android Storage Access Framework for imports and Android Sharesheet for exports.

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

Domain use cases cover device management, connection, foreground-game recognition, manual memory access, cheat import and lookup, one-shot cheat execution, FTP synchronization, editing, ZIP export, sharing, and dmnt detachment. UI code never sends protocol commands or interprets opcodes directly.

## 5. Device and session behavior

Users can add, edit, delete, and select multiple device profiles from a top-level drop-down. The last selected profile is restored at launch.

Switching devices closes the old sockets but does not clear game information or cheat check states. Each device separately retains its last recognized TID, BID, bases, loaded file, and checked groups. On reconnection, the app automatically refreshes identity and bases:

- If TID and BID still match, bases are refreshed and check state remains.
- If TID or BID changed, the old state remains as history, the UI marks the game as changed, and the matching file for the new game is loaded.
- Cached bases are display-only until revalidated.
- Memory actions are disabled until identity validation completes.
- Reconnection, device switching, and identity refresh never replay a checked cheat.

## 6. Screens and interactions

### 6.1 Game screen

The screen contains the device selector, connection status, connect/disconnect action, and a prominent **Recognize current game** button. Recognition retrieves and displays the current Title ID, Build ID, main base, and heap base. It then looks up:

```text
atmosphere/contents/<TID>/cheats/<BID>.txt
```

When found, the corresponding cheat groups load automatically. Otherwise, the app reports that no cheat file exists for the current version and offers import or FTP download.

The screen also contains **Download from Switch**, **Upload to Switch**, and **Detach dmnt** actions. All require a selected, reachable device; file operations additionally require a validated TID and BID.

### 6.2 Cheats screen

The screen displays the current TID, BID, mirror path, and cheat groups. It defaults to view mode.

- Checking a group immediately validates and executes it once.
- Successful groups remain checked and show their last execution time.
- Unchecking changes UI state only and never restores memory.
- A transition from unchecked to checked is the only UI event that triggers execution.
- Incompatible groups show the unsupported opcode and source line and cannot be checked.
- During execution the affected group cannot be tapped repeatedly.

Edit mode allows changes to group names and code. Saving reparses and validates the whole file, writes through a temporary file, updates the index, and returns to view mode. Leaving with unsaved changes requires confirmation. Saving a syntactically valid file may retain groups that use unsupported instructions, but those groups remain visibly non-executable.

### 6.3 Memory screen

The memory tool supports absolute, main-relative, and heap-relative addresses. Values support hexadecimal bytes, signed or unsigned integer widths of 8/16/32/64 bits as applicable to the UI control, Float, and Double. Encoding is always little-endian. Reads and writes are separate operations; manual writes show an address, byte count, and value confirmation. A single read is limited to 4 KiB by default.

### 6.4 Settings and library

Settings manages device profiles and protocol ports, connection and command timeouts, imported cheat files, and local mirror entries. Notes use a dedicated editor for `notes.txt` and never become cheat instructions.

## 7. Supported Atmosphère cheat subset

The first release uses a strict allowlist. A selected group executes only if every instruction is recognized and supported.

- `0x0`: static writes of 1, 2, 4, or 8 bytes to supported main, heap, or absolute addressing forms.
- `0x4`: load a 64-bit constant into a temporary register.
- `0x5`: load a register from supported main/heap relative memory or dereference a register address, covering common `580F0000` and `580F1000` pointer chains.
- `0x6`: write a 1, 2, 4, or 8-byte static value to a supported register-derived address, covering common `640F0000` writes.
- `0x7`: legacy integer addition and subtraction, including common `780F0000` offsets.
- `0x9`: integer add, subtract, shifts, AND, OR, XOR, and move. Floating-point operations are excluded.
- `0xA`: write a register value through supported register, fixed-offset, main-relative, or heap-relative address forms.

The release excludes conditionals, else blocks, loops, key triggers, saved/static registers, debug logging, Alias/ASLR regions, floating-point register arithmetic, freezing, and periodic execution. Unsupported forms produce a source-located validation error and are never silently ignored.

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
- Fake sys-botbase server tests cover fragmented responses, serialization, timeouts, disconnects, reads, writes, and partial execution.
- Fake passive FTP server tests cover missing directories, downloads, staged uploads, rename fallback, overwrite confirmation, notes, and size verification.
- Fake Noexs server tests assert correct framing and command value `0x18`, timeouts, repeat taps, and device targeting.
- Repository tests cover per-device state isolation, TID/BID lookup, mirror replacement, notes, and migrations.
- UI tests cover recognition, no-match behavior, check-to-execute, uncheck-without-rollback, edit/view modes, device switching, ZIP sharing, and disabled unsafe actions.
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

