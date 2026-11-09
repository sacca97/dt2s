# DoubleTap2Sleep

Double-tap the lock screen or home screen to lock your device.

## Setup

- Requires Android 11 or later.
- In the app, choose which screens should support double-tap.
- Enable DoubleTap2Sleep in Android Settings → Accessibility.

The home-screen option works with your selected launcher.

## Four-tap lockdown

Optional. Tap four times quickly (each tap within about 150 ms of the last) to lock and require your PIN, pattern or password instead of biometrics. It uses the device-admin "force lock" policy, granted from the Permissions section of the app (remove it there too; Android blocks uninstalling while it is active). Three taps do nothing, and a normal double tap is unaffected. This relies on `lockNow()` requiring strong authentication afterwards, which can vary by device, so check that biometrics are rejected after a four-tap.
