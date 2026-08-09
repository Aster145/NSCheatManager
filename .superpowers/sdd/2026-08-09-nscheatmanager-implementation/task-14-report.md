# Task 14 report

## Delivered

- Added a centralized `ErrorMapper` and stable resource-backed `UserMessage` model for protocol, session, parser/VM, memory, settings, FTP, ZIP, Noexs, and external-link failures.
- Diagnostic details are structured and sanitized: category, controlled operation key, source line/opcode, and validated IPv4/port only. Payloads, credentials, exception messages, and stack traces are excluded.
- Cancellation is never converted into a user error. Game and editor effects now carry typed messages instead of raw exception text.
- Added localized English and Simplified Chinese messages for all mapped categories.
- Added accessible edit-mode toggle semantics and kept the decorative checkbox out of the accessibility tree.
- Added FullFlow coverage for one-shot effect consumption across composition recreation, edit-mode toggle state, and the 320 dp game shell. Existing lifecycle tests cover immutable write confirmation consumption, lock reconciliation, upload/import/share confirmation claims, editor draft process restoration, selected device, and locale restoration.
- Fix round 1 adds typed `OperationContext` routing. Sys-botbase, Noexs, FTP, ZIP, share, editor, settings, and memory failures no longer share ambiguous string operation names; only network contexts retain a validated endpoint and the configured service port. Every Noexs transport/result failure maps to the Noexs category.
- Added exhaustive subtype coverage for protocol, FTP, and cheat-validation errors, including size/verification/base FTP failures, all validation forms, cancellation propagation, and diagnostic redaction.
- Added a production `MainActivity` composition factory used by an API 28 `ActivityScenario` lifecycle harness. Confirmed write, freeze, upload/direct upload, ZIP import, and share mutations execute exactly once through recreate/background transitions; pending confirmations survive before consumption and disappear afterwards; final owner close occurs exactly once.
- Added isolated 320 dp, 1.5x font-scale flows in both English and Simplified Chinese across Game, Cheats/editor, Memory, Settings, and About, plus production recreation coverage for selected device and locale persistence.

## TDD evidence

- `ErrorMapperTest` was observed failing before the mapper/resources existed, then passed after the minimal implementation.
- The GameViewModel raw-payload regression test failed before `GameEffect.UserError`, then passed after central mapping.
- `FullFlowTest` exposed missing toggle semantics and effect-test lifecycle issues before reaching green.

## Verification

On the Android 9 / API 28 emulator, the final full rerun was:

```text
./gradlew testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
BUILD SUCCESSFUL in 34s
225 JVM tests (5 skipped) and 48 instrumentation tests passed
```

The immediately preceding clean run built/linted/assembled and passed all 48 instrumentation tests, but one pre-existing FTP concurrency test encountered an intermittent test-side `ConcurrentModificationException`. Its isolated rerun passed, followed by the successful complete matrix above.

## Fix round 2

- Corrected `GameViewModel.showFailure` to preserve its typed operation parameter. Effect-level tests traverse production entry points and verify Noexs protocol failures use `error_noexs` with port 7331, FTP uses port 21, and ZIP/share never retain an endpoint.
- Replaced the test-only alternate composition root with injectable `MainActivityDependencies`. Production and tests now always create the normal ViewModels, lifecycle-aware collectors, and `NSCheatManagerApp` tree. The API 28 harness performs a real confirmed memory write and lock, backgrounds/recreates the Activity without replay, and verifies one final session close.
- The bilingual 320 dp tests now inject an explicit 1.5 font-scale `LocalDensity` as well as localized configuration, and assert critical editor, Game, and Memory control bounds remain within the root.

Lint completed with zero errors. Remaining compiler messages are pre-existing API deprecation advisories.
