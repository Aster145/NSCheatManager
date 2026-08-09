# Task 13 report — typed memory tool and locking

## Delivered

- Production `MemoryViewModel` and responsive Compose `MemoryScreen`, wired to the Memory bottom-navigation destination and the live `DeviceSession` owned by `GameViewModel`.
- Absolute, Main-relative and Heap-relative unsigned hexadecimal targets; all existing integer, floating-point and raw hexadecimal `ValueType` options; fixed Switch little-endian encoding.
- Typed/raw reads with 1–4096 byte validation, absolute result address, raw bytes, decoded value, timestamp and clipboard action.
- One-shot writes with an immutable confirmation containing operation key, target, type and defensive byte snapshot. Confirmation is claimed once and revalidated by `DeviceSession` before the exact bytes are written.
- Native checkable Lock control. A successful freeze records the one resolved absolute address and defensive bytes; all parameters remain disabled until the exact recorded address is unfrozen.
- Existing robust DeviceSession freeze cancellation, timeout, ambiguous-disconnect cleanup and pending-cleanup accounting is reused for prepared locks. Device/game generation changes revoke confirmations, results and lock trust while preserving pending cleanup display.
- Localized Simplified Chinese and English labels/errors and compact scroll-safe UI/accessibility semantics.

## TDD and verification

- RED observed before `MemoryViewModel` existed.
- JVM tests cover immutable/single-claim writes, game requirement and 4 KiB bound, exact lock/unlock address, disabled locked parameters, and session-generation invalidation.
- Compose instrumentation covers reachable compact controls, native toggle semantics and immutable confirmation byte presentation.
- `:app:testDebugUnitTest`: passed.
- `:app:connectedDebugAndroidTest`: 37 tests, 0 failures, 0 errors (emulator API 28).
- `:app:lintDebug`: passed.
- `:app:assembleDebug`: passed.

## Notes

- The existing Material/Compose clipboard API emits a deprecation warning; it remains API-26-compatible and is isolated to the copy action.
- No Task 14 functionality was added.
