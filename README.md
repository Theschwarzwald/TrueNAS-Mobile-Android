# TrueNAS Mobile

An Android app (Kotlin / Jetpack Compose) that makes it easier to manage a TrueNAS SCALE /
Community Edition server from a phone or tablet: system status, apps, files, installing apps
from Discover, and the full web interface one tap away in **Expert mode**.

It also works well on a big screen. Run it with **Samsung DeX** (or any Android desktop mode)
and it switches to a desktop-style layout with a side menu and mouse-friendly controls, so you
get a desktop TrueNAS dashboard from your phone or tablet.

> **Heads-up: this app was vibe coded.** The creator built it with an AI assistant to make
> TrueNAS easier to use on phones and tablets, and has only minimal programming knowledge.
> It works for the creator, but the code hasn't been professionally reviewed and may have
> rough edges. Use it at your own risk. Issues and pull requests are welcome, but the creator
> may not be able to fix things quickly (or at all).
>
> This is an unofficial, community project. It is not made by or affiliated with iXsystems
> or the TrueNAS project.

## Install
Download `TrueNASMobile.apk` from the [latest release](../../releases/latest) and open it on
your Android device (allow "install unknown apps" when asked). Requires Android 10 or newer.

## Works with
TrueNAS SCALE / Community Edition 25.04 or newer. The app talks to the JSON-RPC WebSocket API
(`/api/current`).

## Features
- **Sign in** with your TrueNAS admin account over http or https (2FA supported). Over https
  the app creates its own API key on the NAS; delete that key on the NAS to sign the device
  out remotely.
- **App lock** with a passcode or fingerprint/face.
- **Home**: system health, charts and quick links.
- **Apps**: installed apps (bulk select, update all, delete), install from Discover, browse containers.
- **Files**: browse folders on the NAS and download files.
- **Expert mode**: the full TrueNAS web interface inside the app (always behind the app lock).
- **Adaptive layout**: bottom bar on phones, side rail on tablets and unfolded foldables,
  side menu in desktop mode (Samsung DeX, or a mouse on a big screen). Cards never straddle a
  foldable's hinge.
- **Performance & display**: automatic performance levels, high refresh rate, pure-black OLED
  theme, all overridable in Settings.

## Build it yourself
See [SETUP.md](SETUP.md). In short: open this folder in Android Studio, let Gradle sync
(say yes if it offers to set up the Gradle wrapper), then press ▶ Run.

## Where things live
| Folder | What it does |
|---|---|
| `app/src/main/java/com/example/truenasapp/data` | TrueNAS API client, sign-in, encrypted storage |
| `app/src/main/java/com/example/truenasapp/ui` | All screens and the adaptive layout |
| `app/src/main/java/com/example/truenasapp/lock` | App lock (passcode / biometrics) |
| `app/src/main/java/com/example/truenasapp/perf` | Automatic performance and display settings |
