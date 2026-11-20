# TrueNAS Mobile – setup

Works with **TrueNAS SCALE / Community Edition 25.04 or newer** (it uses the
JSON-RPC WebSocket API at `/api/current`, which replaced the REST API that TrueNAS 26 removed).

## 1. Create the project in Android Studio
1. File → New → New Project → **Empty Activity** (the Compose one).
2. Name: `TrueNAS Mobile` · Package name: `com.example.truenasapp` · Language: Kotlin
   · **Minimum SDK: API 29 (Android 10)** · Build config: Kotlin DSL.
3. Finish and let Gradle sync.

## 2. Add the libraries
Open `app/build.gradle.kts`. Make sure `minSdk = 29`, then add these lines inside the
existing `dependencies { ... }` block (keep everything already there):

```kotlin
implementation("androidx.compose.material:material-icons-extended:1.7.8")
implementation("androidx.lifecycle:lifecycle-process:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("androidx.biometric:biometric:1.1.0")
implementation("androidx.fragment:fragment-ktx:1.8.5")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
implementation("io.coil-kt:coil-compose:2.7.0")
implementation("io.coil-kt:coil-svg:2.7.0")
implementation("androidx.window:window:1.3.0")
```
Click **Sync Now**. (If Android Studio suggests newer versions, accepting them is fine.)

## 3. Copy the code in
1. Delete the generated `MainActivity.kt` (you can leave the generated `ui/theme` folder; it's unused).
2. Copy everything from this repo's `app/src/main/java/com/example/truenasapp/` into your
   project's `app/src/main/java/com/example/truenasapp/` (in some templates the folder is `kotlin/` instead of `java/` – either works).
3. Replace `app/src/main/AndroidManifest.xml` with the one in this repo.

## 4. Run
Plug in your phone (or start an emulator) and press ▶ Run.

Sign in with your TrueNAS **admin** account and the address you use in the browser,
e.g. `192.168.1.10` (no `http://` needed; plain http is the default). `https://`
addresses work too with no certificate setup: the app accepts whatever certificate the NAS
has, remembers it, and only asks you to sign in again if it ever changes.

Over http the app keeps your password (encrypted) instead of making an API key, because
TrueNAS revokes API keys used over http. If the NAS has "Web Interface HTTP -> HTTPS
Redirect" turned on, turn it off or the app can't connect over http.

With an https address, after signing in you'll see a new key under **Credentials → API Keys** on the NAS named
"TrueNAS Mobile - <your phone>". That key is what keeps you signed in. Deleting it there
signs the phone out remotely (handy if you lose the phone).

## Package name different?
If you picked another package name, change the first `package ...` line (and the
`import com.example.truenasapp...` lines) in every file to match.

## Using your own logo
The app shows `app/src/main/res/drawable/app_logo.xml` (a placeholder) on the login and lock screens.
To use your own image: delete `app_logo.xml` and put a PNG named `app_logo.png` in the same
`res/drawable` folder. No code changes needed.

For the home-screen icon: in Android Studio right-click `res` → New → **Image Asset**,
choose your image as the foreground, and click Next → Finish. It replaces `ic_launcher`.

## Expert mode
The **Expert mode** button (bottom of Home and Settings) opens the full TrueNAS web
interface inside the app. It always asks for your passcode or fingerprint/face first; if you
haven't set an app-lock passcode yet, it asks you to create one. Sign in to the web UI once
and it remembers you. To stay signed in longer there, raise the session timeout in the web
UI under your user menu → Preferences.

## Installing apps from Discover
Apps install with TrueNAS's default settings. Some apps need extra answers (passwords,
folders...). If TrueNAS says so, tap **Set up in Expert mode** to finish in the web installer.

## Performance & display (Settings)
Everything is automatic by default and can be overridden:
- **Performance**: starts at Maximum, eases off to Balanced / Battery saver when the phone is
  hot, low on memory, dropping frames, or in Battery Saver, and steps back up when things recover.
- **Refresh rate**: highest the screen supports (e.g. 120 Hz), 60 Hz in battery saver.
- **Theme / Pure black (OLED)**: Android doesn't report the screen type, so "Automatic" uses
  a best guess (HDR-capable screens) and always uses pure black in battery saver.
- **Stay connected after leaving the app**: 5 min at full power, 2 min balanced, 30 s saver.
Layouts adapt on their own: bottom bar on phones, side rail on tablets/unfolded foldables,
side menu in desktop mode (DeX or a mouse on a big screen), and cards never straddle a
foldable's hinge.
