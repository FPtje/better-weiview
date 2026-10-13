# Better Weiview

A minimal Android viewer for the Weiview Wi-Fi ear cam. It has one **Connect** button,
enabled while the phone is on the camera's Wi-Fi, which opens a full-screen live view.

The camera's proprietary protocol is reimplemented clean-room from
[`docs/icap-protocol.md`](docs/icap-protocol.md). Nothing from the vendor app is used:
Play Protect flags it as malware.

## Build and install

Requirements: JDK 21 and the Android SDK (platform 37.2, build-tools 36).

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Turn on the ear cam and join its Wi-Fi (`weiview-XXXX`) on the phone.
2. Open Better Weiview. The button becomes enabled once the camera answers at
   `192.168.1.1` (detected by reachability, so no location permission is needed).
3. Tap **Connect** for full-screen video. Press back to stop.

All camera traffic is bound to the camera's Wi-Fi network, so mobile data can stay on.

## Laptop prototype

`tools/icap_client.py` (Python stdlib only) implements the same protocol:

```sh
python3 tools/icap_client.py --iface wlan0 --seconds 20 --out out.mjpeg
ffplay -f mjpeg out.mjpeg
```

`--iface` matters when another interface is also on 192.168.1.0/24.

## Layout

- `app/src/main/java/nl/falcop/betterweiview/icap/`: protocol (`IcapClient`) and the
  camera's Blowfish variant (`CamBlowfish`)
- `.../net/CameraNetworkMonitor.kt`: Wi-Fi detection and network binding
- `.../video/MjpegRenderer.kt`: decodes MJPEG and draws it on a SurfaceView
- `.../MainActivity.kt`: Compose UI
