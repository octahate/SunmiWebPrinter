# SUNMI V2 Network Printer (experimental)

An Android 7.1-compatible print-server app that exposes the **built-in SUNMI V2 thermal printer** to computers over a local network using **IPP 1.1/2.0**. The source is provided for building and testing on a real device.

## Features

- IPP port **8631**, path `/ipp/print` (`ipp://<SUNMI-IP>:8631/ipp/print`).
- Bonjour/DNS-SD `_ipp._tcp` advertisement with printer details (works on many macOS / CUPS setups).
- Advertises **58 mm paper** with ~**48 mm printable area** (384 dots at ~203 dpi).
- Advertises 58 × 50, 100, 150, 200, and 300 mm page sizes.
- Accepts PDF, PNG and JPEG print jobs; rasterizes and dithers them to exactly 384 black/white dots per row.
- Prints in memory-bounded, 256-row bands, avoiding a huge bitmap on the V2's limited memory.
- Uses SUNMI's maintained `com.sunmi:printerlibrary:1.0.24` dependency. No root required.
- IPP status and jobs; simple health endpoint at `/health`; persistent Android foreground service while enabled.
- IPP/2.0 replies and core printer attributes required by CUPS `get-printer-attributes.test` (added in v0.2).

## Build

Use JDK 17, Android SDK 35 and Gradle 8.10.2:

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, push the entire project to a new GitHub repository and run **Actions → Build Android APK → Run workflow**. Download the `sunmi-v2-network-printer-debug` artifact. This repo includes `.github/workflows/build-apk.yml` so you do not need a local Android SDK.

## Setup

1. Install the APK on the **original SUNMI V2**, connect the SUNMI and your computer to the same private Wi-Fi network.
2. Open the app, tap **Start network printer**, then **Print test receipt**. Leave the foreground service running.
3. On macOS, **System Settings → Printers & Scanners → Add Printer → IP**. Address: SUNMI's Wi-Fi IP, Protocol: **Internet Printing Protocol (IPP)**, Queue: `ipp/print`. If there is a driver choice, try AirPrint/IPP (driverless), not an ESC/POS driver.
4. On Linux/CUPS, try `lpadmin -p sunmi-v2 -E -v ipp://SUNMI_IP:8631/ipp/print -m everywhere`. The server is a minimal IPP implementation: some CUPS versions may require a manual PPD or additional required IPP attributes.
5. On Windows, try adding `http://SUNMI_IP:8631/ipp/print` as an IPP printer. Driverless discovery is not guaranteed.

Verify reachability by visiting `http://SUNMI_IP:8631/health` from your computer. The app's printer UI also shows the URL.

## How page sizing works

- **Physical paper:** 58 mm roll.
- **Printable horizontal dots:** 384 (~48 mm at 8 dots/mm).
- **Margins:** 5 mm on the left and 5 mm on the right when receiving a true 58 mm-wide PDF. They correspond to the printer's physical unprintable side areas.
- **Height:** variable-length roll; the IPP implementation exposes five practical selectable heights. Each PDF page prints as one continuous receipt; it does not eject or cut.
- **Resolution:** advertised as 203 × 203 DPI (approximately 8 dots/mm).
- **Wide documents:** A4 and other wide PDFs are scaled to 48 mm printable width rather than cropped.
- **Raster images:** scaled to a 384-dot width; aspect ratio preserved. The 8×8 ordered-dither matrix produces 1-bit pixels supported by the SUNMI SDK.

## Current limitations / caveats

This is a **first implementation, not fully device-tested or IPP Everywhere certified**. The IPP/2.0 capability response fixes the concrete CUPS version/attribute test failure, but does not establish complete driverless/AirPrint compatibility.

- The original V2 runs Android 7.1. On that version, `NsdManager` cannot advertise the `_universal._sub._ipp._tcp` subtype required for reliable iOS AirPrint discovery. This build should not be advertised as an iPhone AirPrint solution.
- IPP `Print-Job`, `Validate-Job`, `Cancel-Job`, `Get-Job-Attributes`, `Get-Jobs`, and `Get-Printer-Attributes` are implemented. Not every advanced IPP operation or raster format is supported.
- The SDK's `printBitmap` callback confirms submission, not necessarily that the receipt finished successfully. Queue state is best-effort.
- Receipt lengths are limited to 50,000 raster rows per document page, and upload size to 30 MiB.
- Printer status may lag. `updatePrinterState()` is consulted before printing each strip.
- The printer has **no paper cutter**. Additional paper feed occurs after each job.
- **Security:** no TLS or authentication. Anyone on the same accessible network can print. Do not use on a public/untrusted network.
- Battery optimizations and the SUNMI OS may stop network listening in the background; keep the app active/whitelisted.
- You may need to test manual IPP configuration on the target computer and adjust required IPP attributes.

## Technical approach

The Android foreground service binds `InnerPrinterManager` to `SunmiPrinterService`. A small IPP-over-HTTP server accepts PDF or image files, queues them, renders into 384×256 `ARGB_8888` strips, dithers to exact black/white, and sends each strip using `printBitmap`. Long jobs stream through temporary files. The HTTP/IPP codec is implemented in pure Java, without a heavyweight CUPS server.

## References

- [SUNMI Printer Developer Docs](https://file.cdn.sunmi.com/SUNMIDOCS/SunmiPrinter-Developer-Docs-1-1.pdf)
- [SUNMI V2 developer notes, including recent SDK compatibility caveats](https://github.com/zeck00/sunmi-v2-rescue/blob/main/printer.md)
- [SUNMI printer library on Maven Central](https://central.sonatype.com/artifact/com.sunmi/printerlibrary)

## Troubleshooting on macOS

After installing the rebuilt APK, stop and restart the printer server, then run:

```sh
ipptool -tv ipp://Android.local:8631/ipp/print get-printer-attributes.test
```

This should no longer report the IPP/1.1-vs-2.0 version mismatch or the six previously missing printer attributes. macOS may still not offer AirPrint automatically because PWG Raster and Apple URF are not implemented. For now, do not choose a random DYMO/ESC-POS/PostScript/PCL driver.

For a diagnostic, also test `lpadmin -p SUNMI_V2 -E -v ipp://Android.local:8631/ipp/print -m everywhere`. Failure after the attributes test can indicate additional printer-protocol compatibility gaps rather than a network problem.
