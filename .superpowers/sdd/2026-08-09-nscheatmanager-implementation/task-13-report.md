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
- `:app:connectedDebugAndroidTest`: 41 tests, 0 failures, 0 errors (emulator API 28).
- `:app:lintDebug`: passed.
- `:app:assembleDebug`: passed.

## Notes

- The existing Material/Compose clipboard API emits a deprecation warning; it remains API-26-compatible and is isolated to the copy action.
- Fix round 1 replaced every confirmation byte array with `ImmutableBytes`, added operation-key/local claims and cancellation gates for stale completions, made readiness explicit in UI state, and derives locks/pending cleanup from the authoritative session snapshot.
- Fix round 2 adds non-cancellable continuation barriers for late read/write/lock/unlock success and failure after session replacement, proving a newer claim and authoritative lock state cannot be overwritten. Exact little-endian boundary tables now cover every scalar type; raw reads cover 1 and 4096 bytes and reject both short and long device responses.
- Compose coverage now hosts the screen at an explicit 320 dp width with isolated English and Simplified Chinese resource configurations, verifies ready/disconnected and checked/disabled merged toggle semantics, localized confirmations/time, unclipped controls, and exact clipboard content without changing the process locale.
- Fix round 3 replaces Kotlin runtime `assert` calls with unconditional JUnit assertions and verifies explicit left/right bounds for the action row plus Read, Write and Lock. Each locale checks localized Lock text, toggle role/state and disabled state on the same merged tagged semantics node.
- Confirmation UI now identifies the localized address mode, hexadecimal input, resolved absolute address, selected type/input, exact byte count and bytes. Result time uses the locale-aware platform time formatter.
- Floating-point input deliberately preserves Java/Kotlin IEEE-754 parsing semantics: `NaN`, positive/negative infinity and signed negative zero are accepted and encoded using raw little-endian bits. Integer range validation remains strict.
- No Task 14 functionality was added.
