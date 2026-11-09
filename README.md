# DoubleTap2Sleep

Double-tap the lock screen or home screen to lock your device.

## Setup

- Requires Android 11 or later.
- In the app, choose which screens should support double-tap.
- Enable DoubleTap2Sleep in Android Settings → Accessibility.

The home-screen option works with your selected launcher.

## Triple-tap lockdown

Optional. Tap three times quickly to lock and require your PIN, pattern or password instead of biometrics. It uses the device-admin "force lock" policy, granted from the Permissions section of the app (remove it there too; Android blocks uninstalling while it is active). While it is on, a normal double tap waits about 300 ms for a possible third tap before locking; with it off, nothing is delayed. This relies on `lockNow()` requiring strong authentication afterwards, which can vary by device, so check that biometrics are rejected after a triple tap.
