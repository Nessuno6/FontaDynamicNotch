<h1 align="center">Fonta's Dynamic Notch for Android</h1>

<p align="center">
  <b>A Dynamic Island for Android tablets and phones.</b><br>
  It hangs from the top of the screen around the camera, shows what's going on, and springs open into a control panel.
</p>

<p align="center">
  <a href="../../releases/latest/download/DynamicNotch.apk"><b>⬇ Download DynamicNotch.apk</b></a>
  &nbsp;·&nbsp; <a href="#install">Install</a>
  &nbsp;·&nbsp; <a href="#features">Features</a>
  &nbsp;·&nbsp; <a href="#build">Build</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84" alt="Android 12+">
  <img src="https://img.shields.io/badge/made_for-One_UI_9-1259E6" alt="Made for One UI 9">
  <img src="https://img.shields.io/badge/license-MIT-555555" alt="MIT license">
</p>

An Android port of [FontaDynamicNotch](https://github.com/Fonta-lab/FontaDynamicNotch) by Fonta-lab, the macOS
Dynamic Island for the MacBook notch. The Mac app lives on this fork's `main` branch; the Android app is this
`android` branch. It is built and tested on a Samsung tablet and phone with One UI 9 (Android 17), and runs on any
Android 12 or newer.

## Features

**The closed notch** sits over the status bar, centred on the camera, and grows wings for what's going on. It is
never wider than it has to be: every time something comes or goes, both sides are measured again and balanced so
the notch stays centred with no empty gaps.

- **Music:** the album cover on one side, and moving bars or One UI 9 style waves on the other.
- **Live notifications:** timers, calls, navigation, deliveries, downloads and Samsung's Now Bar items, all at once,
  with their icons and live numbers. A lone one splits across the notch, icon on one side and text on the other.
- **Your own timers and stopwatch**, counting down beside it.
- **BentoBar items** as small square wings, left or right in any order, each with its icon and live value.
  Tap or point at one and BentoBar's own menu for it opens right there.

**The open notch** (tap it, or point at it with a mouse or S Pen):

- **Music:** the cover travels into the panel, with title, artist, controls, a seek bar (flat or One UI 9 waves),
  media volume, and the output button that opens Samsung's own output switcher.
- **Brightness** slider.
- **Files:** a shelf you drop files on (drag them onto the notch or share them to "Notch shelf"), then share them
  again, Quick Share them or open them.
- **Tools:** timers with an alarm, a stopwatch and counters.
- **Live:** every live notification as a card with its progress and buttons; dismiss any of them.
- **BentoBar:** all of BentoBar's items, with their menus; hold one to pin it beside the closed notch.

**On the whole screen**

- **Live blur** behind the notch (Samsung's own blur where available), always or only when open.
- **Corners that match the device's screen**, and a thin border in any colour (white by default).
- **One UI icons** throughout, many taken from One UI itself.
- **Hide Samsung's Now Bar and status bar chip** while the notch shows them instead; they come back when the notch
  is switched off.
- **Hide BentoBar's own bar** while its items live in the notch; external displays keep theirs.
- **Quick Settings tile** to switch the notch on and off.
- **Hides in full-screen apps** together with the status bar.
- **120 Hz animations:** while it moves, the notch asks for the screen's top refresh rate.
- **Update check:** once a day it asks GitHub for this app's latest release and tells you with a notification when
  there's a newer one (Settings › Advanced › Updates, where you can also check now or turn it off). That's the only
  thing the app ever goes online for, and nothing about your device is sent.
- Themes, springs, layout, music and timer settings, all in the app.

## Install

1. Download [DynamicNotch.apk](../../releases/latest/download/DynamicNotch.apk) from the latest release and open it.
2. Open **Fonta's Dynamic Notch**, then in **General › Setup** turn on:
   1. **Show the notch**: the accessibility service that draws it
   2. **Music controls**: notification access (also for live notifications)
   3. **Brightness slider**: modify system settings
   4. **Timer notifications**
3. Optional: add the **Dynamic Notch** tile to Quick Settings.

Press and hold the open notch to get back to Settings.

### Optional extras

- **Hide Now Bar** needs a permission only adb can give once (or root):
  ```sh
  adb shell pm grant com.fonta.dynamicnotch android.permission.WRITE_SECURE_SETTINGS
  ```
- **BentoBar in the notch** needs [BentoBar with item sharing](https://github.com/Nessuno6/bentobar/tree/notch-items)
  (see its releases): turn on **Look › Other apps** in BentoBar, then **BentoBar** in the notch's settings.

## How the Mac features map to Android

| Mac | Android |
|---|---|
| Notch window over the menu bar | Accessibility overlay over the status bar and camera cutout |
| Hover to open | Tap to open, tap outside to close; mouse and S Pen hover too |
| Spotify now playing | Any app with a media session, through notification access |
| System volume, output picker | Media volume; the system's own output switcher |
| Keyboard backlight | Removed: Android has no such API |
| File shelf, AirDrop | Shelf filled by drag and drop or the share sheet; Quick Share |
| Hidden in full-screen apps | Hidden whenever the status bar is hidden |

## Build

```sh
./gradlew :app:assembleRelease
```

Needs JDK 17+ (`JAVA_HOME` can point at Android Studio's `jbr`) and Android SDK 37. The release build is the one
to judge animations by: Compose runs far slower in a debug build.

## Credits

- [FontaDynamicNotch](https://github.com/Fonta-lab/FontaDynamicNotch) by Fonta-lab, the original macOS app (MIT).
- [One UI icons](https://github.com/thatjoshguy67/oneui-icons) by thatjoshguy67 (see `ONEUI-ICONS-LICENSE`).
