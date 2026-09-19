# IP Webcam

Turns an Android phone (Android 10+) into an IP camera you open in a browser: live video and audio,
plus push-to-talk from your microphone to the phone's speaker.

The app idles as a plain TCP listener. The camera, microphone, encoders and wake locks are only
active while an authenticated viewer is connected, and everything is released a couple of seconds
after the last one leaves.

## How it works

```
Browser (WebCodecs) ⇄ HTTPS + WebSocket on one port ⇄ StreamService (foreground service)
                                                       ├─ WebServer       TLS, login, static web UI, /ws
                                                       ├─ SessionManager  standby ⇄ streaming
                                                       ├─ VideoPipeline   Camera2 → hardware H.264 (zero-copy surface)
                                                       ├─ AudioCapture    microphone → Opus (AAC fallback)
                                                       └─ TalkbackPlayer  browser mic PCM → speaker
```

- **One TCP port** carries everything, so a router port-forward, a VPN (Tailscale/WireGuard) or an
  HTTP tunnel (Cloudflare Tunnel, ngrok) all work. Making the phone reachable is up to you.
- **Browser support**: needs WebCodecs — current Chrome, Edge, Safari or Firefox.
- The server, TLS, auth and session code is plain JVM Kotlin with no Android dependencies, which is
  why it is covered by ordinary unit tests, including an end-to-end test over real TLS.

## Build

The only full JDK this was verified with is JDK 26 (any JDK 17+ *with `javac`* should work; a
runtime-only package such as Fedora's `java-21-openjdk-headless` does not).

```sh
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
export JAVA_HOME=/usr/lib/jvm/java-26

./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` produces a minified (~4 MB) but **unsigned** APK; sign it with your own
key (`apksigner`) before installing. For personal use the debug APK is fine.

`targetSdk` is deliberately 36 while `compileSdk` is 37: targeting 37 brings Android's new
local-network permission, which needs testing on a device before it is adopted.

## Use

1. Open the app, tap **Start listening**, and grant the camera (required), microphone and
   notification permissions. The service must be started from the app — Android only lets a
   service open the camera from the background if it was started while the app was visible.
2. Allow background activity when asked, so Doze does not cut the listener off while the phone sleeps.
3. Open one of the `https://…` addresses shown in the app. The browser warns about the self-signed
   certificate: compare its SHA-256 fingerprint with the one in the app, then accept it.
4. Log in with the password shown in the app (generated on first run; change it under *Password*).
5. Press **Connect**. The camera turns on now, and off again when you disconnect or close the tab.

Web UI controls: mute, hold-to-talk (half-duplex: incoming audio is muted while you talk), switch
camera, torch, quality, rotate (remembered per browser), fullscreen. Video pauses automatically
while the tab is hidden; audio keeps playing.

Up to 4 viewers can watch at once; one can talk at a time.

### Exposing it to the internet safely

- Prefer a VPN such as Tailscale or WireGuard **running on your router or another always-on box**,
  not on the phone: a VPN client on the phone must stay awake itself and defeats standby savings.
- With a port-forward, pick a high random port. Scanners probing the port wake the phone briefly
  each time.
- With Cloudflare Tunnel or a reverse proxy, point it at `https://<phone-ip>:<port>` with origin
  certificate verification disabled (`noTLSVerify: true`), enable *Behind a reverse proxy or
  tunnel* in the app so viewer addresses are read from the proxy headers, and add the public
  hostname under *Extra certificate hostnames*. There is no plain-HTTP mode on purpose.
- The password is the only thing protecting the camera. Keep the generated one or use something
  equally strong. Login attempts are rate-limited, sessions expire after 12 h idle / 30 days, and
  changing the password signs everyone out. *Recent connections* in the app shows who logged in.

### iOS / Safari

Safari may refuse the WebSocket to a self-signed certificate even after you accept the page
warning. Download `https://<phone>:<port>/cert.crt` on the iPhone, install the profile, and enable
it under *Settings → General → About → Certificate Trust Settings* — or put the phone behind a
tunnel with a real certificate.

## Battery behaviour

| | Standby | Streaming |
|---|---|---|
| Camera, microphone, encoders | released | active (hardware H.264, no preview, screen can be off) |
| Wake lock / Wi-Fi lock | none (see modes) | held |
| Work being done | one thread blocked in `accept()` | encoding + one writer thread per viewer |

An inbound connection wakes the phone through the Wi-Fi chip; a 30 s wake lock then covers the
handshake. Standby modes (Settings → Standby):

- **Automatic** (default): hold a wake lock in standby only while charging.
- **Maximum battery**: never hold one.
- **Always reliable**: always hold one. Use this if connections time out while the phone is
  unplugged with the screen off — some firmware does not wake reliably for inbound packets.

*Stop streaming on low battery* disconnects viewers (and refuses new ones) below the chosen level
while unplugged. Some vendors (Xiaomi, Huawei, Samsung, …) add their own app killers; if the
listener dies in the background, also exempt the app there (see dontkillmyapp.com).

## On-device test checklist

This project was built and unit-tested without a device attached, so none of the following has
been verified on hardware yet:

1. Deny the microphone, grant the camera → listener starts video-only, no crash. Grant both →
   notification shows "Standby".
2. Privacy indicators appear only after pressing **Connect** in the browser, and vanish within a
   few seconds of closing the tab. `adb shell dumpsys power | grep -i webcam` shows no
   `webcam:stream` lock in standby.
3. Screen off, unplugged: `adb shell dumpsys deviceidle force-idle`, then load the page → it
   should answer within ~3 s. Repeat after an hour of real idle, in each standby mode, and note
   the overnight standby drain.
4. Video latency under ~0.5 s on the LAN; orientation correct for both cameras (use **Rotate** if
   the phone is mounted sideways); camera switch and torch work; a second viewer starts instantly;
   throttling one viewer (DevTools) does not disturb the other.
5. Audio stays in sync for 30 minutes; hold-to-talk is audible on the phone; a second viewer is
   refused while the first is talking.
6. Chrome (desktop and Android), Firefox, Safari (macOS and iOS).
7. Open the stock camera app mid-stream → the viewer sees a notice and video resumes when the
   camera is free again. Toggle Wi-Fi → the listener survives and the addresses refresh. Reboot →
   a "tap to resume" notification appears and works.
8. Wrong passwords are delayed; the fingerprint in the app matches the browser's certificate viewer.

If audio is silent in every browser but video works, the device's Opus encoder output may not be
what browsers expect; please capture `adb logcat` while connecting.

## Known limitations

- The listener cannot restart unattended after a reboot: Android forbids starting a camera or
  microphone service from the background. The app posts a notification; one tap resumes it.
- Android's quick-settings camera/microphone privacy toggles produce black video or silence without
  any error the app could report.
- Talkback is half-duplex and uncompressed (16 kHz PCM, ~256 kbit/s while talking).
- The certificate is reissued when the phone gets an address it has not had before, which makes
  browsers ask for the exception again.

## Project layout

```
app/src/main/kotlin/dev/aten/webcam/
  server/   HTTP parser, WebSocket codec and connection, TLS web server        (pure JVM)
  tls/      self-signed certificate generation and storage                     (pure JVM)
  auth/     passwords, sessions, login rate limiter, audit log                 (pure JVM)
  session/  standby ⇄ streaming state machine, per-viewer backpressure, wire protocol (pure JVM)
  media/    Camera2 → H.264, microphone → Opus, talkback playback, H.264 bitstream helpers
  service/  foreground service, power locks, boot receiver, network addresses
  data/     settings and UI-observable state
  ui/       Compose screen
app/src/main/assets/web/   web client (WebCodecs player, audio worklets)
```
