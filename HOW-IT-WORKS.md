# How Fonta's Dynamic Notch for Android works

The details behind [the README](README.md): how the notch does what it does on Android, what root adds, how the Mac
app's features map to Android, and how to build it.

## Under the hood

### Drawing over the status bar

Android lets an app draw over the status bar only from an **accessibility service**, so that's what draws the notch:
one window across the top of the screen. Only the notch's own shape takes touches; a tap anywhere else goes straight
to the app below. The shape comes from the screen itself: the camera cutout's place and size, and, with **Match the
device's corners**, the screen's own corner radius.

**Live blur** is a second window right behind the notch, cut to the notch's shape on every frame of its animation. It
uses Android's background blur, or Samsung's own blur where One UI has it, and stays on the closed notch too if you
like.

### The closed notch and its wings

Everything beside the closed notch (the album cover, the music bars or waves, live notifications, your timers,
BentoBar's items) is measured as it is now, text by its glyphs so a ticking clock never makes it twitch. Both sides
then reach as far as the longer one, so the notch stays centred on the camera. Parts that may go either side fill the
shorter side first; a lone live item splits across the notch (icon left, words right) when that evens it out; and what
room is left goes to the parts with words, or to BentoBar's tiles, never to an empty gap. All of it is measured again
whenever something comes or goes.

### Music, live notifications, the Now Bar

- **Music** comes from the media sessions Android shares with apps that have **notification access**: any player,
  not just one. The output button sends SystemUI the request that opens Samsung's own output panel (Android's output
  dialog elsewhere).
- **Live notifications** come from the same access: ongoing notifications with a clock, a progress bar or Android's
  promoted ("live") flag, and Samsung's Now Bar items with their chips. Dismissing one in the Live tab removes it
  from the notch, and from Android too where you could swipe it away yourself; one its app keeps up (a running
  timer, a call) stays in Android.
- **Hide Now Bar** switches off Samsung's own status bar chip (Settings.Secure `quickstar_indicator_hide_ongoing_chip`)
  and the lock screen's Now Bar (Settings.System `now_bar_enabled`), so live items don't show twice. Both come back
  whenever the notch is off. See [Root](#root) for what that needs.

### Full screen and the Quick Settings tile

To hide with the status bar, the notch watches **which windows are on screen and where** (never what's in them): when
SystemUI's status bar window leaves the top of the screen, as in a full-screen video or game, the notch hides too, and
comes back with it. The **Quick Settings tile** switches the notch off and on; off, the Now Bar, the chip and
BentoBar's own bar all come back.

### BentoBar in the notch

[BentoBar](https://github.com/Nessuno6/bentobar/tree/notch-items) shares its items with apps you allow (its **Look ›
Other apps** switch, then Android's permission). The notch reads the items with their icons and live values, shows
the ones you pin as wings and all of them in its BentoBar tab, and asks BentoBar to open an item's own menu right
under the wing you tap, or the one the mouse rests on. When the notch opens, it asks BentoBar to close that menu.

While it shows items, the notch **claims** them: BentoBar leaves them out of its own bar, or hides its whole bar with
**Hide BentoBar's own bar**. A claim lasts two minutes and the notch renews it every minute, so if the notch ever stops
without saying so, BentoBar's bar comes back by itself. The notch lives on the device's own screen only, so a second
display (a monitor in DeX) always keeps BentoBar's whole bar.

### Smooth animations

Android 15 and newer give a window's ordinary redraws the screen's "normal" rate, 60 Hz on many tablets. While the
notch moves (opening, closing, wings resizing), both its windows ask for the screen's **top refresh rate**, so the
spring runs at 120 Hz from its first frame, touch or no touch; when it settles, they ask for nothing.

### Updates

Once a day (Settings › Advanced › Updates: on by default, with **Check now**), the notch asks GitHub's API for this
repository's latest release: one request, carrying nothing about the device. A newer one is announced once with a
notification. Tapping it opens the update screen, the device's own dialog (One UI's on Samsung): the first time,
Android asks you to let the app install apps; then the APK is downloaded and handed to Android's package installer.

## Root

The notch works fully without root. Root makes one thing complete: **Hide Now Bar**.

| | Without root | With root |
|---|---|---|
| **Status bar chip** of live notifications | hidden, after a one-time `adb` command ([Optional extras](README.md#optional-extras)) | hidden: the app grants itself the permission |
| **Now Bar** on the lock screen | stays; switch it yourself in Settings › Lock screen and AOD › Now bar | hidden too, so live items show only in the notch |

Both come back by themselves when you switch the notch off (its Quick Settings tile or the setting) or its service
stops, so Samsung's own surfaces return whenever the notch isn't there to show your live items. Root is used only to
change those two settings, when Hide Now Bar or the notch itself is switched on or off.

With root, [BentoBar](https://github.com/Nessuno6/bentobar/blob/notch-items/HOW-IT-WORKS.md#root) also reads more for
the notch's BentoBar wings: CPU and GPU temperatures, the apps using the CPU and memory, the battery's real cycles and
health, and a Galaxy watch's or earbuds' levels.

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
to judge animations by: Compose runs far slower in a debug build. Release builds are signed with the debug key of the
machine that builds them (see `app/build.gradle.kts`), and an update installs only over a build signed with the same key.
