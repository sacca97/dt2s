# DoubleTap2Sleep

Android app (Kotlin, Compose). An accessibility service locks the device on a double tap; an
optional triple tap uses device admin `lockNow()` to require the PIN (lockdown).

## Commands

- Build: `ANDROID_HOME=$HOME/Android/Sdk ./gradlew --offline :app:assembleDebug`
- Unit tests: `ANDROID_HOME=$HOME/Android/Sdk ./gradlew --offline :app:testDebugUnitTest`
- Release: `make release VERSION=x.y.z` (bumps version, tags, pushes; CI builds the APK)

## Before changing behaviour

Read `docs/STATE_MACHINES.md`. Gesture timing lives in `TapGesture.kt` and update-card state in
`UpdateState.kt`; both are pure and covered by tests in `app/src/test`, so add a test with any
change. The launcher/foreground tracking in `LockTapService.kt` is not testable off-device: do
not refactor it without a phone, and say so in the summary if you touched it.
