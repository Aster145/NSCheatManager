# Task 11 report

Implemented the Material 3 application shell, settings/device editor, localization, and About page.

## Delivered

- Cheat-first navigation with Game, Cheats, and Memory bottom destinations plus Settings/About overflow destinations.
- Responsive settings UI tested at 320 dp, with multiple fixed-IPv4 profiles, default selection, CRUD editor, and configurable sys-botbase/FTP/Noexs ports.
- Validation for required/unique names, canonical unique IPv4 addresses, and ports in `1..65535`; defaults remain `6000`, `21`, and `7331`.
- Application-scoped Room/DataStore/DeviceRepository composition root and a narrow ViewModel factory boundary.
- AndroidX AppCompat per-app locales for `zh-CN` and `en`, persisted in AppPreferences with a compare-before-apply recreation guard.
- About page using `BuildConfig.VERSION_NAME`, open-source credits, GPL/risk guidance, exact QQ group number and approved HTTPS link, with localized no-handler feedback.
- Chinese label `NS金手指管理` and English label `NSCheatManager`.

## TDD and verification

Observed RED failures before implementing settings, About, ViewModel validation/CRUD, editor, and navigation behavior. Final verification on `emulator-5554`:

- `./gradlew testDebugUnitTest connectedDebugAndroidTest` — PASS (21 instrumentation tests)
- `./gradlew lintDebug assembleDebug` — PASS
- `git diff --check` — PASS

Lint completes with existing/advisory warnings and no errors.
