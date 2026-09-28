# Privacy Policy — DJM Rec for Android

_Last updated: 28 September 2026_

DJM Rec for Android ("DJM Rec", "the app") records audio from a USB DJ mixer to your
Android phone. This policy explains what data the app handles and why. The app is
open source: you can check everything described here in the source code at
<https://github.com/P2GR/DJM-Rec-for-Android>.

## Summary

- Your recordings, videos and file names stay on your phone. They are never uploaded
  to us.
- The app has no user accounts of its own and shows no ads.
- Optional diagnostics (crash reports and mixer connection events) are sent to
  Google Firebase to improve mixer compatibility. They never include recorded audio,
  file names or personal data. You can turn them off at any time in
  **Settings > Diagnostics & privacy**.
- Livestreaming sends your audio (and camera video, if you choose) only to the
  streaming service you select, using your own account or stream key.

## Recordings and files

Audio recordings (WAV, FLAC, MP3) are saved in `Music/DJMRec` and videos in
`Movies/DJMRec` on your phone. They are only shared when you choose to share, export
or stream them yourself.

## Permissions

| Permission | Why the app needs it |
| --- | --- |
| USB host access | To receive audio from your mixer over USB. |
| Microphone (`RECORD_AUDIO`) | Android requires this permission to capture any audio input, including USB audio from your mixer. |
| Camera (optional) | Only for video recording and video livestreams, when you turn them on. |
| Notifications | To show the recording status while recording in the background. |
| Do Not Disturb access (optional) | To silence notifications during a set, if you enable it. |
| Internet | For livestreaming and optional diagnostics. |

## Diagnostics (Firebase Crashlytics and Google Analytics for Firebase)

To find and fix problems with specific mixers and phones, the app can send diagnostic
data to Google Firebase (a service of Google LLC). Diagnostics are **on by default** and
can be turned off in **Settings > Diagnostics & privacy**. When they are off, the app
sends no automatic diagnostics.

When diagnostics are on, the following may be sent:

- **Crash reports**: the error and stack trace, app version, phone model, Android
  version and technical state at the time of the crash (for example free memory).
- **Mixer connection events**: the mixer's model name, USB vendor and product IDs and
  USB audio configuration descriptors, connection stages and audio health measurements
  (for example buffer underruns). USB serial numbers are not collected.
- **App usage events**: which app features and recording stages are used, together
  with the phone model, Android version, app version and approximate country derived
  by Google from the network connection.
- **Identifiers**: a random Firebase installation / app instance ID, used to group
  events from the same installation. It is not linked to your name, email or any
  account, and the advertising ID is not collected.

Before anything is sent, the app removes URLs, file paths, stream keys, tokens and
passwords from log text.

**Manual bug reports**: if you send a bug report from **Support & diagnostics**, it is
delivered through Firebase Crashlytics even when automatic diagnostics are off. It
contains the description you type, your phone and Android version, the connected
mixer and its USB descriptors, your app settings and recent app logs, with the same
redaction applied. Please do not include personal information in your description.

Crash reports are kept for up to 90 days and analytics data for up to 14 months, after
which Firebase deletes them. Firebase processes data under Google's privacy policy:
<https://policies.google.com/privacy>. More about Firebase privacy:
<https://firebase.google.com/support/privacy>.

## Livestreaming

- **YouTube**: when you go live on YouTube, you sign in with your Google account
  through Google Sign-In. The app uses the YouTube Data API (YouTube API Services) to
  create, monitor and end the live broadcast on your channel, and sends your audio and
  video stream to YouTube. The access token is kept only on your phone and is only
  sent to Google. By using this feature you agree to the YouTube Terms of Service
  (<https://www.youtube.com/t/terms>) and Google's privacy policy
  (<https://policies.google.com/privacy>). You can revoke the app's access at any time
  at <https://myaccount.google.com/permissions>.
- **Mixcloud and custom RTMP servers**: the stream key and server address you enter
  are stored on your phone and only sent to that server.

The app's use of information received from Google APIs adheres to the Google API
Services User Data Policy, including the Limited Use requirements.

## Sharing

We do not sell data and do not share it with anyone except the service providers
described above (Google Firebase for diagnostics, and the streaming service you choose
yourself).

## Children

DJM Rec is a tool for DJs and is not directed at children under 13.

## Your choices

- Turn off diagnostics in **Settings > Diagnostics & privacy**.
- Revoke YouTube access at <https://myaccount.google.com/permissions>.
- Uninstalling the app removes its settings and stored stream keys from your phone.
  Your recordings in `Music/DJMRec` and `Movies/DJMRec` stay until you delete them.
- To ask a question or request deletion of diagnostic data, open an issue at
  <https://github.com/P2GR/DJM-Rec-for-Android/issues>.

## Changes

If this policy changes, the updated version will be published at this address with a
new "Last updated" date.
