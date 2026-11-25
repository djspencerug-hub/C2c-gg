# C to C — Direct USB-C Android Screen Mirroring

Multi-module Android Studio project (`:usblink`, `:sender`, `:receiver`) for low-latency H.264 screen mirroring from a Samsung Galaxy Note 20 (`Sender`) to a Redmi Android phone (`Receiver`) over a direct USB-C / OTG data connection (no Wi-Fi, no Bluetooth, no internet).

## Modules
- `usblink`: USB Host & AOA v1/v2 handshake (`AoaProtocol`), runtime USB permission + busy-interface diagnostics (`UsbHostManager`, `UsbLinkLogger`), framed bidirectional packet channel (`UsbPacketChannel`), connection state tracker (`Connected` / `Searching...` / `Error`), auto-reconnect controller & settings (`UsbReconnectController`, `UsbSettingsStore`), battery telemetry (`LocalBatteryMonitor`), latency tracker (`LatencyTracker`), and OTG help strings.
- `sender`: Foreground `MediaProjection` + hardware H.264 `MediaCodec` encoder (`MirrorService`), live rotation & 720p/1080p resolution menu, Start/Stop Mirroring, USB reconnect, remote battery readout, auto-reconnect toggle, OTG Help modal, and latency overlay.
- `receiver`: USB Host `Activity` + low-latency `MediaCodec` H.264 decoder bound directly to `SurfaceView` (`H264SurfaceDecoder`), status banner, Reconnect, remote battery readout, settings toggle, OTG Help modal, latency overlay, and USB log viewer.

## Option 1: Build with Android Studio
1. Open the `CtoC-android` folder in Android Studio.
2. Allow Gradle to sync (JDK 17, AGP 8.5.2, Gradle 8.7, Android SDK 34).
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Option 2: Build via Command Line
```bash
gradle wrapper
./gradlew :sender:assembleDebug :receiver:assembleDebug
cp sender/build/outputs/apk/debug/sender-debug.apk C-to-C-Sender-debug.apk
cp receiver/build/outputs/apk/debug/receiver-debug.apk C-to-C-Receiver-debug.apk
zip CtoC-debug-APKs.zip C-to-C-Sender-debug.apk C-to-C-Receiver-debug.apk
```

## Option 3: Build Automatically via GitHub Actions (No Local Android Studio Required)
Push this `CtoC-android` folder to a GitHub repository. `.github/workflows/build-apks.yml` compiles both debug APKs and uploads `CtoC-debug-APKs` as a downloadable workflow artifact.

## USB-C & OTG Connection Instructions
1. Install `C-to-C-Sender-debug.apk` on the **Samsung Galaxy Note 20** and `C-to-C-Receiver-debug.apk` on the **Redmi**.
2. Connect with a USB-C to USB-C data cable.
3. If USB Host/Accessory role negotiation fails on a direct cable, plug a **USB-C OTG adapter** into the **Redmi** (forcing USB Host mode) and run a **USB-A to USB-C data cable** to the **Galaxy Note 20**. On Xiaomi/Redmi, enable **Settings → Additional settings → OTG** if needed.
4. Accept the USB Host permission prompt on the Redmi, then tap **Start Mirroring** and accept the `MediaProjection` prompt on the Galaxy Note 20.
