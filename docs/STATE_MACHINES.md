# State machines

Every piece of behaviour in this app that depends on "what happened before" is one of the
machines below. Machines marked **explicit** are modelled as a sealed type or enum with pure,
unit-tested transitions. Machines marked **implicit** are still spread across fields in
`LockTapService`; they are timing-dependent on real devices and cannot be tested on the JVM, so
change them only with a device in hand.

| # | Machine | Where | Status |
|---|---------|-------|--------|
| 1 | Tap gesture (double tap lock, triple tap lockdown) | `TapGesture.kt` | explicit, tested |
| 2 | Update card (check / download / notices) | `UpdateState.kt` | explicit, tested |
| 3 | Interaction mode | `LockTapService.calculateMode()` | derived enum, no stored history |
| 4 | Launcher confirmation | `LockTapService` fields | implicit |
| 5 | Screen / unlock lifecycle | `LockTapService.screenStateReceiver` | implicit |
| 6 | Permissions (accessibility, device admin, install) | `MainActivity`, `LockdownAdminReceiver` | derived from system state |

## 1. Tap gesture (`TapGesture`)

```
Idle ──tap──▶ FirstTap ──tap 40..300 ms later──▶ Armed(taps=2) ──tap, lockdown on──▶ Armed(taps=3)
  ▲              │ slower/faster tap: restart as FirstTap        │ any other tap: Idle (cancelled)
  └──────────────┴── cancel() / confirm() / second tap rejected ◀┘
```

- The caller owns the clock and the confirm timer. `Outcome.Armed` carries the delay to wait:
  150 ms normally, 300 ms after tap 2 when lockdown is enabled, 150 ms after tap 3.
- When the timer fires, `confirm()` returns whether to lock down (3 taps) or just lock (2).
- A click, scroll or window change after the first tap calls `isVetoedBy()` and cancels.
- Rule of thumb: a plain double tap must never be delayed unless lockdown is enabled.

## 2. Update card (`UpdateUiState`)

`phase` is `Idle | Checking | Downloading(progress)`. `notice` is one of `None | CheckFailed |
InstallFailed | InstallPermissionNeeded`. Starting a phase clears the notice. A late progress
callback is ignored once the download has ended. The "install permission" round trip: the
download button sets the notice and opens system settings; `onResume` clears it and retries.

## 3. Interaction mode (`InteractionMode`)

`OFF | LOCKSCREEN | LAUNCHER`, recomputed from scratch by `calculateMode()` from screen state,
keyguard, foreground package and the two gesture settings. It is a pure function of inputs, not a
state with transitions, so any event may call `updateMode()` safely. A mode change cancels the
gesture in progress.

## 4. Launcher confirmation (implicit)

Fields: `foregroundPackage`, `launcherConfirmedAt`, `launcherTransitionFromPackage`,
`lastWindowStateEventTime`. Window snapshots can lag behind the home transition, so a fresh launcher
window event is trusted for `LAUNCHER_TRUST_WINDOW_MS`. `postUnlockRefreshRunnable` re-reads the
snapshot once things settle. Do not simplify without testing on a device across launchers.

## 5. Screen / unlock lifecycle (implicit)

`SCREEN_ON`, `SCREEN_OFF` and `USER_PRESENT` broadcasts reset foreground tracking and recompute the
mode. Accessibility event types are narrowed to the minimum for the current mode
(`updateAccessibilityEventTypes()`).

## 6. Permissions

Never stored, always read live: accessibility via `ENABLED_ACCESSIBILITY_SERVICES`, device admin
via `DevicePolicyManager.isAdminActive`, installs via `canRequestPackageInstalls`. Lockdown is on
only if the `lockdown_taps` pref is true *and* admin is active. `LockdownAdminReceiver.onDisabled`
clears the pref so re-granting admin later does not silently re-enable it.
