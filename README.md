# Widscan58 for Android

A handheld spectrum analyzer for the [Widscan58](../repo) ESP32-C5 5.8 GHz FPV
band scanner. Plug the board into the phone over USB-C and the app talks to it
directly as a serial device — no Wi-Fi, no bridge, no root.

Built as a companion to the browser UI in `repo/web`; the wire protocol and the
display engine are ports of it, so the numbers mean the same thing in both.

## Requirements

- Android 8.0 (API 26) or newer, with USB OTG.
- A Widscan58 board (ESP32-C5) running the widscan58 firmware. Its native USB is
  the Espressif USB-Serial/JTAG port (VID `0x303A`), which enumerates as a CDC-ACM
  device with one bulk pair. Flash it first with `repo/tools`, or from any
  computer — the app itself does not flash.
- A cable that carries data, not charge-only.

## Build and install

```sh
cd app
./gradlew :core:test :mobile:assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

The toolchain is Gradle 8.14 + AGP 8.11 + Kotlin 2.2, JDK 17. `local.properties`
must point at your Android SDK (`sdk.dir=…`).

Then: connect the board (OTG adapter or a direct USB-C phone), open **Widscan58**,
grant the USB permission dialog. The app appears in the attach filter, so plugging
the board in can also open it by itself.

## What the screen shows

| Part | Content |
| --- | --- |
| status line | profile, rotations/s, hops, gain, link fill, link name |
| band chips | one tap puts a whole band (R/A/B/E/F/L) on the device; `Max`/`Avg` toggle the hold traces |
| panorama | every LO segment stitched on a nearest-LO basis: live trace, max hold, average, per-bin noise floor, RSSI/search windows per channel, plan LO markers, board spurs |
| waterfall | one row per completed scan rotation (~60/s), scaled against the smoothed noise floor |
| channel tiles | level (EMA when reported), bar over floor−10…floor+40 dB, SNR, floor, badges, 10 s sparkline |
| bottom bar | Start/Stop, manual gain 0…63, Reset holds |

Tap the chart or a tile to select a channel. Long-press the chart fits the dB
range to what is on screen (it is remembered).

## How it talks to the board

`PROTOCOL MONITOR` first — the firmware boots silent in RotorHazard mode. Then
`SCAN STOP`, the stored plan (`CHANNELS …`, `MODE …`, `GAIN MANUAL …`),
`SPEC 4 256 40000000 MAX`, `PROFILE?`, `STATUS?`, `SPURS?`, `SCAN START`.
Telemetry is 4 Hz even when stopped, so no frames for 3 s means the board
restarted; the app re-runs the handshake by itself.

Frames are the binary `0xA5 'R'` packets with a CRC32; `PROFILE`/`STATUS` replies
are JSON. All of it is decoded in `:core`, off the USB read thread, and applied
on the main thread.

## Modules

- `core/` — pure Kotlin/JVM: frame parser, CRC, stream resync, command runner,
  band plans, the store (segments, max hold, average, per-LO floor), panorama
  compositing, waterfall rows, channel tiles. No `android.*` imports, covered by
  49 JVM tests.
- `mobile/` — Android: USB CDC host (`usb/`), the JSON reply codec, preferences,
  the instrument screen and its canvas views.

## Not in this version

Channel zoom, RSSI history pages, the channel-plan editor, RotorHazard mode,
power-meter mode, RAW IQ capture, the diagnostics console, and record/replay.
The log/probe screen is still reachable as the **USB probe** launcher entry, for
when the board shows up but not as a CDC port.

## Verification

`:core:test` is green on the JVM. The UI compiles into an APK but has not been
driven on a real phone yet — the panel geometry, touch targets and the USB
handshake need a device (and the board) to confirm.

## License

GPL-3.0-or-later, same as the upstream firmware and web UI, whose authors and
the ESP32-SDR / C5VRX work it builds on are credited in `repo/README.md`.
