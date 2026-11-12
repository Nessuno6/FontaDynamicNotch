# Fonta's Dynamic Notch for Android

An Android port of [FontaDynamicNotch](https://github.com/Fonta-lab/FontaDynamicNotch) (macOS, MIT).
A notch hangs from the top of the screen around the camera cutout. Closed, it shows the album cover and
animated bars, or a running timer. Tap it and it springs open into a control panel.

## What was ported, and how

| Mac feature | Android version |
|---|---|
| Notch window over the menu bar | Accessibility overlay over the status bar and cutout |
| Hover to open | Tap to open, tap outside to close. Mouse and S Pen hover also work |
| Spotify now playing | Any app with a media session (Spotify, YouTube, …), via notification access |
| System volume, mute | Media volume, mute |
| Output-device picker | Shows the current output; the button opens Android's own output picker |
| Screen brightness | Same (needs "Modify system settings") |
| Keyboard backlight | Removed: Android has no such API |
| Timers, alarm, stopwatch, counters | Same, with wall-clock persistence and an exact alarm as backstop |
| File shelf, AirDrop, Trash | Shelf filled from the share sheet ("Notch shelf"); Quick Share via the share sheet; remove |
| Themes, layout, springs, settings | Same, in the app's Settings screen (themes import/export as JSON) |
| Hidden in full-screen apps | Hidden whenever the status bar is hidden |

Tablets get the original wide layout; phones get a stacked layout.

## Build

```sh
./gradlew :app:assembleDebug
```

Needs JDK 17+ (`JAVA_HOME` can point at Android Studio's `jbr`) and Android SDK 37.

## Setup on the device

Open the app, then in **General › Setup** turn on:

1. **Show the notch**: the accessibility service
2. **Music controls**: notification access
3. **Brightness slider**: modify system settings
4. **Timer notifications**

Press and hold the open notch to get back to Settings.
