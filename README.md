<div align="center">

# DJM Rec for Android

**Record your DJ sets from a compatible USB mixer directly to your Android phone.**

[![Download the latest APK](https://img.shields.io/badge/Download-latest%20APK-3ddc84?style=for-the-badge&logo=android&logoColor=black)](https://github.com/P2GR/DJM-Rec-for-Android/releases/latest)

![Android 10+](https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=black)
![ARM64](https://img.shields.io/badge/CPU-ARM64-444?logo=arm&logoColor=white)
![MIT](https://img.shields.io/badge/License-MIT-blue)

Requires Android 10+, a 64-bit ARM phone with USB host support, and a USB data cable.
Install the **release APK**; the debug APK is for testing.

</div>

## Supported devices

| Device | Support |
| --- | --- |
| **DJM-450, DJM-750MK2, DJM-900NXS2, DJM-A9, DJM-V10** | ✅ Supported |
| DJM-V5, DJM-S11 | 🧪 Experimental |
| XDJ-XZ, XDJ-AZ, OPUS-QUAD, OMNIS-DUO | 🧪 Experimental |
| XDJ-RX3 | ⚠️ Not possible — USB audio is playback-only |
| Other USB audio interfaces | May work with a compatible USB audio input |

Use the mixer's **PC/Mac USB audio port** with a USB data cable. Experimental support is not a
guarantee. On the XDJ-RX3, use MASTER REC to USB storage instead. Pro DJ Link and USB protocol
research live on the separate
[`experimental` branch](https://github.com/P2GR/DJM-Rec-for-Android/tree/experimental).

## Start recording

1. Connect the mixer's **PC/Mac USB port** to your phone with a USB data cable, open DJM Rec and
   allow USB access.
2. Play audio and check both meters; automatic arming starts monitoring, not recording.
3. Press **Record**, then **Save set** when finished. Files appear in **Sets** and `Music/DJMRec`.

Format, gain and USB channel pair are in **Recording setup** (0 dB gain keeps the input level).
Keep USB connected during a set: force-stop, reboot or cable loss ends capture.

## Features

<table>
  <tr>
    <td width="33%" valign="top"><h3>🎙️ Studio recording</h3><p>WAV, FLAC and MP3 (320 kbps) with pause/resume and an optional MP3 copy.</p></td>
    <td width="33%" valign="top"><h3>🛡️ Safe capture</h3><p>-1 dBFS safety limiter, 15-second pre-record buffer, leading-silence trim and auto-stop.</p></td>
    <td width="33%" valign="top"><h3>🌊 CDJ waveform</h3><p>3-band RGB waveform with stereo meters and clip warnings.</p></td>
  </tr>
  <tr>
    <td width="33%" valign="top"><h3>✂️ Set editor</h3><p>Trim, fades and loudness normalization (BS.1770 LUFS); export to MP3, WAV or FLAC.</p></td>
    <td width="33%" valign="top"><h3>🔴 Livestream &amp; video</h3><p>YouTube, Mixcloud and RTMP streaming with local recording and MP4 video.</p></td>
    <td width="33%" valign="top"><h3>📱 Long-set ready</h3><p>Background recording, persistent notification, set library, battery and heat warnings.</p></td>
  </tr>
</table>

## Privacy

Optional Firebase diagnostics (crash reports and bounded mixer-connection events) help improve
mixer compatibility. No recorded audio, filenames or personal data is collected. Turn it off in
**Settings > Diagnostics & privacy**.

## ☕ Buy me a coffee

If DJM REC saved your set, you can support the project:

<div align="center">

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-ffdd00?style=for-the-badge&logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/p2gr)

</div>

## License

MIT. Bundled libraries retain their own licenses.
