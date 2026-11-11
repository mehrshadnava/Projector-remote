# Projector Remote

Turn your phone into a remote, trackpad and keyboard for a cheap Android projector. It also adds a
lamp-off mode and real 4-corner keystone correction that the stock firmware hides behind a bad IR remote.

Two small apps, no Gradle, no libraries:

| Folder | Runs on | What it does |
|---|---|---|
| `phone-remote/` | your phone (Android 12+) | Pretends to be a **Bluetooth keyboard + mouse + media remote**. Nothing to install on the projector for basic control. |
| `projector-helper/` | the projector | **Screen off** (lamp dimmed, Android stays awake) and **Keystone** (4-corner correction), opened from the phone. |

## Features

**Phone app**
- **Remote tab:** round D-pad (hold arrows to repeat), Back / Home / Menu / Keystone, Vol −, Mute, Play/Pause, Vol +
- **Touchpad tab:** drag to move a real cursor, tap = click, two-finger drag = scroll, two-finger tap = back,
  hold-able Left/Right click buttons for dragging
- **Keyboard tab:** letters appear on the projector as you type, plus Esc / Tab / arrows / Space / Enter
- Phone volume buttons control the projector while the app is open
- Power button: **tap** = lamp off/on, **hold** = real standby
- Stays connected in the background (foreground service) and reconnects automatically
- Notification with **Screen**, **Play/Pause** and **Stop** buttons

**Projector helper**
- **Screen off:** black screen with the backlight (lamp) at its minimum. Music/video audio keeps playing,
  and volume and media keys don't wake it. Any arrow or OK turns it back on.
- **Keystone:** full-screen alignment grid. Arrows move the selected corner, OK = next corner,
  Menu = reset, Back = done. Corners are saved by the firmware and survive reboots.

## How it works

- **Remote:** the phone registers as a Bluetooth HID device (`BluetoothHidDevice`) with a composite
  descriptor: keyboard (report 1), relative mouse with wheel (report 2), consumer control (report 3).
  The projector sees an ordinary Bluetooth keyboard and mouse.
- **Opening the helper from the phone:** Android launches apps for certain "app keys". The phone
  sends consumer usage `0x192` (AL Calculator → `KEYCODE_CALCULATOR`) for Screen off and `0x18E`
  (AL Calendar → `KEYCODE_CALENDAR`) for Keystone. The helper's activities claim
  `CATEGORY_APP_CALCULATOR` / `CATEGORY_APP_CALENDAR`, which nothing else on the projector uses.
- **Screen off:** an activity sets `screenBrightness = 0`. The firmware clamps that to its minimum
  backlight (10/255 here). It keeps the screen "on" because putting this firmware to sleep makes it
  power off, and Bluetooth can't wake it from that.
- **Keystone:** the firmware warps the whole screen in SurfaceFlinger (`libkeystone.so`). Its system
  service `keystone` (`android.app.IKeystoneService`) accepts calls without a permission check, so the
  helper calls it directly over Binder. See [Firmware notes](#firmware-notes).

## Tested hardware

- **Projector:** generic "TG033" mini projector, firmware `tg033_TUMAN-eng_root…`. The settings screen claims
  Android 14, but it is really **Android 7.1 (SDK 25)** on a Snapdragon 410 (msm8916) with an 800×480 panel.
- **Phone:** Samsung Galaxy S23.

The remote works with almost any Android projector, TV box or PC that accepts a Bluetooth keyboard.
Screen off and Keystone depend on this firmware's internals and will need changes for other models.

## Build

The script builds with the plain SDK tools (no Gradle). It runs in **Git Bash on Windows**.

1. Get the toolchain into `C:\android-build`, or point `TOOLS=` somewhere else:
   - `jdk/`: any JDK 17 (e.g. Eclipse Temurin)
   - `platform/`: contents of Google's `platform-35_r02.zip` (needs `android.jar`)
   - `build-tools/`: contents of `build-tools_r35.0.1_windows.zip`
2. [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) in `C:\platform-tools`
   (or set `ADB=`).
3. Build:

```bash
./build.sh install                                    # phone app, onto the USB-connected phone
PROJECTOR=192.168.1.50:5555 ./build.sh helper install # projector helper, over Wi-Fi ADB
```

`debug.jks` is committed **on purpose**. It's a throwaway debug key (password `android`), so every clone
signs the same way and can update apps that are already installed. Don't use it for anything else.

## First-time setup

1. **ADB to the projector:** many of these projectors listen on port 5555 out of the box:
   `adb connect <projector-ip>:5555`, then accept the prompt on the projector screen.
2. Install the helper on the projector and the remote on your phone (see Build).
3. **Pair with the app open.** Open the phone app first, tap the status pill → **Pair new**, then pair from
   the projector's Bluetooth settings. If the phone was paired *before* the app existed, unpair it on both
   sides first, otherwise the projector only knows it as a phone and never offers "Input device".
4. In the projector's projection settings, **turn Vertical Keystone (auto) off**, or its tilt sensor will
   overwrite your manual corners.

## Optional: projector cleanup

These cheap firmwares ship with adware and junk. Disabling is reversible (`pm enable <pkg>`):

```bash
adb shell pm list packages -3
```

```bash
adb shell pm disable-user --user 0 <package>
```

Packages disabled on the tested unit: `com.offshore.pikachu` (unknown app that starts at boot with install
and overlay permissions), a fake `com.google.android.verifier` in `/data/app`, `com.adups.fota`
(Adups updater), `com.dangbei.lerad.videoposter`, `com.ty.actionspcbatest`, `com.caf.fmradio`,
`com.qualcomm.qti.lunarinfo`.

Faster menus:

```bash
adb shell settings put global window_animation_scale 0.5
```

(repeat for `transition_animation_scale` and `animator_duration_scale`). Video above 720p only makes an
800×480 panel stutter.

## Firmware notes

Found by reading the projector's `framework.jar`, `services.jar` and `TwdSetting.apk`.

`keystone` service (`android.app.IKeystoneService`), callable from apps and `adb shell service call`:

| Code | Method | Notes |
|---|---|---|
| 1 | `setVerticalCorrectEnable(boolean)` | auto keystone from the tilt sensor |
| 2 | `reset()` | zero all corners and angles |
| 3 | `setZoomValue(int)` | picture size in %, firmware default **80** |
| 4 / 5 / 6 / 7 | `setLeftTop / RightTop / LeftBottom / RightBottomOffset(float x, float y)` | panel pixels, **positive = inward**; stored in `persist.display.lt/rt/lb/rb` |
| 8 / 9 | `setHorizontalDegree / setVerticalDegree(float)` | |
| 10 | `update()` | **required** after changing corners; tells SurfaceFlinger to redraw |

- SurfaceFlinger reads the final corners from `persist.sys.keystone.{lt,rt,lb,rb}` (pixels, y measured
  from the nearer edge) when `persist.sys.keystone.update=1` and something triggers a reload. The stock
  settings app pokes SurfaceFlinger with transaction `1050`, which only system apps may call.
- **Careful:** `service call keystone N` with no arguments still runs the method with zero values.
  Code 3 without an argument sets zoom 0, which collapses the picture. Restore it with
  `adb shell service call keystone 3 i32 80`.
- SELinux is permissive on this firmware.
- The Bluetooth address has no stored value. It changed once after an unclean shutdown, which means
  pairing again (it stayed stable across normal reboots).

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Close other remote apps, then reopen" | Only one app per phone may act as a Bluetooth keyboard. Close other remote apps. On Samsung, make sure the app isn't also installed in **Dual Apps** (the build installs to the main profile only). |
| Not connecting after the projector restarted | Keep the app open for a few seconds, it retries every 4 s. If it never connects, the projector's Bluetooth address probably changed: unpair on both sides and pair again. |
| Keystone corners jump back | Vertical Keystone (auto) is on in the projector settings. Turn it off. |
| Screen off does nothing | The projector helper isn't installed, or another app on the projector claims the Calculator key. |
| Remote drops after a while | Set the phone app's battery usage to **Unrestricted**. |
