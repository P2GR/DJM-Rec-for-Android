# Building DJM Rec

Use JDK 17, Android SDK 35, NDK 26.1.10909125 and CMake 3.22.1. APKs target ARM64 devices
running Android 10 or newer. Emulators cannot validate physical mixer capture.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease
```

On Windows, use `gradlew.bat`. Release signing requires an ignored `keystore.properties`:

```properties
storeFile=/path/to/release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

Never commit the keystore or passwords. `assembleLocal` builds a minified APK with the Android
debug key for local testing; never publish it as a release. `version.properties` defines the
version name and monotonically increasing Android version code.

The tag-triggered GitHub release workflow needs `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` repository secrets. Configure
`BUGFENDER_SYMBOLICATION_TOKEN` for release mapping uploads; see [Bugfender setup](BUGFENDER.md).

Release notes live in `docs/releases/<version>.md`. Publishing a matching `v<version>` tag triggers
verification, signing and publication of release/debug APKs and checksums.
