# Contributing

Small fixes, reproducible bug reports and device test results are especially useful.
Open an issue before a large change so we can agree on the behavior.

1. Fork the repository and create a branch.
2. Follow the README build instructions.
3. Preserve permission checks at playback entry points and asynchronous completions.
4. Run `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`.
5. Describe the trigger, resulting behavior, checks run and checks not run in your PR.

Add meaningful tests for permission, identity, lifecycle and accounting changes.
Keep live YouTube requests out of unit tests; live probes are opt-in.

Include device model, Android version, app version, reproduction steps and expected/
actual behavior in bug reports. Use synthetic data. Never upload a parent PIN,
family information, credentials, signing keys, app preference dumps or signed media
URLs. Redact screenshots/logs. Follow SECURITY.md for sensitive access-control bugs.

This alpha does not establish full playback containment. Do not describe proof
timers as recurring daily allowances or a sampled check as proving all access paths.

Application-code contributions are GPL-3.0-or-later. Preserve third-party notices
and separate artwork/dependency licenses. To regenerate Basil vectors/PNG fallbacks,
run `python3 scripts/import_basil_icons.py` with ImageMagick installed. Normal builds
need no conversion tools.
