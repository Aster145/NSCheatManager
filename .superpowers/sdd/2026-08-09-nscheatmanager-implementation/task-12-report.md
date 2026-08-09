# Task 12 report

Implemented the cheat-first Game/Cheats flow, raw cheat/notes editor, current-game FTP and ZIP actions, and Android ZIP sharing.

## Delivered

- Production `MainActivity` composition for the saved-device repository, `DeviceSession`, sys-botbase, Noexs, local Atmosphere mirror, anonymous FTP synchronization, ZIP service, Game ViewModel, and Editor ViewModel.
- Responsive device selector followed by Connect/Disconnect and Detach dmnt; the 320 dp layout wraps only the two actions onto a second row.
- Exact overflow order: checkable Edit mode, Recognize current game, Download, Upload, Package/share ZIP, Import ZIP, Settings, About. Unsafe actions remain disabled until their validated identity/file prerequisites exist.
- Connect delegates to the session's single connect-and-recognize transition. Missing current-game mirror files expose import and FTP-download actions.
- Source-located unsupported diagnostics and transition-based group execution. Only `false -> true` claims an execution; in-flight duplicate taps are rejected, failures remain unchecked, and unchecking only changes UI/persistence without memory I/O.
- Raw UTF-8 editor tabs named `<BID>.txt` and `<BID>/notes.txt`, syntax reparse before shared-lock atomic replacement, source-line validation feedback, and discard confirmation when leaving dirty edits.
- FTP download overwrite preview, upload preview, and direct-overwrite fallback confirmations as buffered one-shot effects.
- Strict `OpenDocument` byte/size reading and two-phase ZIP inspection confirmation. Imported TID/BID must still match the current validated game before preview and before replacement.
- ZIP export/share through a FileProvider restricted to `cache/shared/cheat-zips/`, with `ACTION_SEND`, ZIP MIME type, ClipData, temporary read permission, Build-ID-scoped notes, and explicit empty-notes confirmation.
- English and Simplified Chinese labels/messages for the new flow. The Memory destination remains the Task 13 placeholder.

## TDD and verification

Observed RED compile/test failures before implementing the wished-for Game/Editor state APIs, Compose screen, document reader, and share service. Final verification on `emulator-5554`:

- `./gradlew testDebugUnitTest` — PASS
- `./gradlew connectedDebugAndroidTest` — PASS (29 instrumentation tests)
- `./gradlew lintDebug` — PASS with advisory warnings and no errors
- `./gradlew assembleDebug` — PASS
- `git diff --check` — PASS

Task 12 adds 10 focused JVM tests and 5 focused instrumentation tests covering single recognition delegation, no-replay effects, missing mirrors, execute/uncheck semantics, unsupported source lines, raw editor/save/dirty handling, 320 dp menu order, strict document bytes, and narrow FileProvider sharing.
