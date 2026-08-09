# Task 15 report — public repository and release candidate pipeline

## Delivered

- Chinese-first bilingual README with actual API 26/version 1.0.0 requirements, fixed ports `6000/21/7331`, exact mirror/notes paths, strict ZIP rules, implemented opcode subset, build/install guidance, safety and unofficial-project notices, exact QQ group/link, GPL-3.0-only notice, and upstream links/licenses.
- Canonical unmodified GNU GPL version 3 license text plus bilingual contribution and responsible security disclosure policies. No private contact address was invented.
- Focused bilingual protocol, compatibility, and troubleshooting references. The screenshots section explicitly records that no verified image assets exist yet rather than inventing them.
- SHA-pinned GitHub Actions: minimal-permission Android CI and a signed release-candidate workflow. Tag pushes build/upload artifacts only. Draft GitHub Release creation requires an explicit manual dispatch checkbox and never publishes the draft.
- Structured bug, feature, and sanitized public security issue forms, release-note categorization, and a Chinese-first release template.
- Cross-platform PowerShell release verifier that requires one Gradle version, one exactly named non-empty APK, and one canonical lowercase SHA-256 line.

## Verification

- Script-first RED: verifier rejected a missing release output directory before implementation artifacts existed.
- `npx --yes yaml-lint ...`: all workflow/template YAML passed.
- All local Markdown links resolve; all workflow action references are pinned to full commit SHAs.
- `LICENSE` is byte-for-text identical (apart from newline normalization) to the installed canonical GPLv3 `COPYING3` text.
- `./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease`: passed.
- Windows PowerShell verifier: valid `1.0.0` APK/checksum passed; mismatched Gradle version and multiple-versioned-APK cases were rejected.
- Verified local candidate checksum: `93c1927ff72d2952aaf22a1187d7e01b74538805bc86d35ed79926fcd1348813`.

## Environment note

The workstation does not have PowerShell 7 (`pwsh`) installed, so the required local invocation was also exercised under Windows PowerShell 5.1. The script uses only APIs supported by both and the GitHub Linux runner executes it with PowerShell 7. The locally assembled release APK is unsigned and validation-only; the workflow refuses to create a candidate without all signing secrets.

No GitHub remote, push, tag, Release, or other external publication was created.
