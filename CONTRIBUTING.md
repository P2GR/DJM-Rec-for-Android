# Contributing to DJM Rec for Android

## Setup

```bash
git clone https://github.com/P2GR/DJM-Rec-for-Android.git
cd DJM-Rec-for-Android
```

JDK 17, Android SDK, NDK 26.1, CMake 3.22.1 required. Open in Android Studio or build via CLI:

```bash
./gradlew assembleDebug
```

## Before submitting

- Lint must pass: `./gradlew lintDebug`
- Tests must pass: `./gradlew testDebugUnitTest`
- Keep ProGuard rules up to date if adding JNI or reflection-based code

## Code style

- Kotlin: official style (configured in `gradle.properties`)
- C++: C++17, `.clang-format` is project-standard
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/)

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
