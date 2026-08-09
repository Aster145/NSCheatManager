# Task 12 fix round 1

## Scope

This round hardens the cheat-first game/editor flow without adding the Task 13 memory UI.

## Changes

- Added immutable `GameOperationKey` identity (device, TID, BID, session generation) and revalidation at execution and file/network publication boundaries. Stale completions and confirmations are discarded; staged downloads are cleaned up.
- Closed the successful-execution repeat window with per-key claims retained until the checked-state acknowledgement arrives. Uncheck remains persistence/UI-only.
- Made editor loading last-open-wins and persisted dirty/discard/navigation state with `SavedStateHandle`.
- Generalized `CheatMirror` publication into a journaled, rollback-capable two-file transaction so cheat text and `notes.txt` publish together.
- Moved ZIP, FTP, direct-overwrite, empty-notes, and dirty-discard confirmations into immutable ViewModel state with stable, single-consumption IDs.
- Added merged toggle semantics for cheat rows, including checked/disabled state and unsupported reason.
- Propagated the persisted last successful execution timestamp from Room through recognition/session state to the UI.
- Replaced presentation-time raw diagnostics with structured kind/line/opcode data and localized English/Chinese rendering.

## Regression coverage

- Device/session switch during download and between check/execution gateway: no wrong-session publication or memory write.
- Delayed checked acknowledgement plus rapid repeated event: exactly one execution.
- Second editor file publication failure: both originals remain unchanged.
- Out-of-order editor loads: latest game wins.
- Stable one-shot ZIP/download/upload/direct/empty-notes/dirty-discard confirmations and duplicate confirmation rejection.
- 320 dp English and Simplified Chinese accessibility for toggle rows, exact source line/opcode, and execution timestamp.

## Verification

- `gradlew testDebugUnitTest lintDebug assembleDebug` (188 unit tests, 0 failed; 4 skipped)
- `gradlew connectedDebugAndroidTest` (31/31 tests, 0 failed)
- `git diff --check`
