# Task 12 fix round 2

## Scope

This round addresses transaction durability, Binder-safe editor draft restoration, and the remaining structured/localized diagnostics. It does not add Task 13 memory UI.

## Changes

- Added a `FileDurability` boundary with Android API 26 `Os.fsync` support for both files and parent directories. The host-JVM implementation uses NIO file/directory force; Windows skips only its known unsupported NIO directory-handle case while file force and all other errors remain fail-closed.
- Added ordered durability barriers for journal creation/replacement, stages, backups, target renames, phase updates, rollback, and cleanup. `CheatMirror` now recovers unfinished transactions when a new mirror instance is created.
- Added test-only transaction cut hooks and reopen tests for INIT, STAGED, BACKED_UP, first target moved, second target moved, PUBLISHED, and interrupted cleanup. Recovery always yields the complete old pair or complete new pair.
- Added a bounded, symlink-safe, UUID-token `FileEditorDraftStore` in app-controlled cache. Draft files use atomic replacement, SHA-256 integrity, identity/key consistency checks, and expiry cleanup.
- Removed cheat text, notes text, and originals from `SavedStateHandle`; it now stores only the small draft token, operation key, tab/dirty flags, and pending navigation/confirmation metadata. A recreated ViewModel restores a near-limit draft while keeping SavedState below 1 KiB.
- Replaced parser English messages with structured diagnostic kinds. Editor parse failures and runtime validation/protocol failures now carry structured line/opcode/arguments and render through English/Simplified Chinese resources.

## Verification

- `gradlew testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`
- JVM: 194 tests, 0 failures, 0 errors, 5 skipped
- Instrumentation: 35/35 tests, 0 failed
- `git diff --check`

