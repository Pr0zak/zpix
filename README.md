<p align="center">
  <img src="docs/logo.png" width="150" alt="zpix logo">
</p>

<h1 align="center">zpix</h1>

<p align="center">A lightweight, fully-offline digital <b>photo frame</b> for Android — giving an old tablet a second life.<br>No accounts, no cloud, no nag screens.</p>

---

A lightweight, fully-offline digital **photo frame** app for Android — built to give an old tablet a second life. No accounts, no cloud, no nag screens.

It's a tiny native shell hosting a WebView; the slideshow engine is plain HTML/CSS/JS, which makes the fancy GPU-accelerated transitions easy.

## Features

- **Local & offline** — reads photos straight from a folder on the device.
- **Transitions**: Ken Burns (slow pan/zoom), crossfade, slide, **scattered collage** (photos at their true aspect ratios, framed and tilted), **floating** (photos drift and zoom across the screen), and an **origami fold**.
- **Settings** (tap the screen): photo folder picker, seconds-per-photo, photos-per-collage, shuffle, clock overlay, and per-transition on/off — persisted in the WebView's localStorage.
- **Always-on**: keeps the screen awake and auto-starts on boot.
- Runs on **Android 5.1+** (API 22).

## Build

No Gradle required — the app has zero third-party dependencies.

```bash
./build.sh        # aapt2 -> javac -> d8 -> zipalign -> apksigner
```

Requires a JDK, the Android SDK (`build-tools` with `aapt2`/`d8`/`apksigner`/`zipalign`, plus a platform `android.jar`). Edit the paths at the top of `build.sh` to match your SDK location. The output is `build/zpix.apk`.

Regenerate the launcher icon (PIL):

```bash
python3 gen_icon.py
```

## Install

```bash
adb install -r build/zpix.apk
```

Put your photos in a folder on the device (e.g. `/sdcard/Pictures`). On first run zpix auto-selects the folder with the most photos; you can change it anytime by tapping the screen to open settings.

## Layout

```
AndroidManifest.xml
src/com/zand/frame/   MainActivity.java (WebView shell + folder bridge), BootReceiver.java
assets/               index.html, app.js (slideshow engine), style.css, logo.svg
res/                  strings + launcher icons
build.sh, gen_icon.py
```
