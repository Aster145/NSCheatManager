# Task 12 fix round 3

## Scope

This round removes editor draft I/O from the main thread and bounds hostile or damaged draft-cache cleanup. It does not add Task 13 memory UI.

## Changes

- `CheatEditorViewModel` now accepts an I/O dispatcher and optional lifecycle-owned scope. Draft cleanup, restore, save, and delete calls all run through interruptible I/O dispatch.
- Draft updates remain immediate in immutable UI state while a conflated 250 ms quiet-period worker serializes persistence. A monotonic generation loop observes edits that arrive during a slow save and publishes the latest snapshot before releasing the write barrier.
- Restore and cleanup are asynchronous. Restored editors expose loading state, and the existing load generation prevents an old restore or game load from replacing a newer open request.
- Bounded suspend flush APIs serialize pending saves. Explicit editor close saves the latest snapshot, then deletes the draft behind the same mutex so no older queued write can recreate it. `MainActivity` explicitly awaits a bounded flush whenever its STARTED lifecycle stops; `onCleared` closes the structured worker best-effort without starting detached work.
- `FileEditorDraftStore` construction is now free of file-system I/O; its root is created lazily by dispatched store operations.
- Cleanup uses a NOFOLLOW directory scan capped at 256 entries, 8 MiB read, and 250 ms. It ignores links/non-regular files and removes stale temporary files plus malformed, oversized, or expired drafts, forcing the directory after mutations.

## Verification

- Focused editor/DraftStore RED tests failed on the old synchronous/missing APIs, then passed after implementation.
- `gradlew testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`
- JVM: 200 tests, 0 failures, 0 errors, 5 skipped.
- Instrumentation: 35/35 tests, 0 failed.
- Lint and debug APK assembly passed.
- `git diff --check`
