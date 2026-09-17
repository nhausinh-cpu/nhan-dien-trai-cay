# Fix Missing Debug Keystore Error

The project is failing to build because the `debugConfig` signing configuration in `app/build.gradle.kts` expects a `debug.keystore` file in the project root, but this file is missing (and is listed in `.gitignore`).

## Proposed Changes

### [Component Name]

#### [MODIFY] [build.gradle.kts](file:///C:/Users/NPH/Downloads/TRO-LI-TRAI-CAY/app/build.gradle.kts)

I will remove the explicit `debugConfig` signing configuration and its usage in the `debug` build type. This allows the Android Gradle Plugin to use its default debug keystore (usually located at `~/.android/debug.keystore`), which is the standard behavior for Android development and avoids build failures when a specific keystore is missing.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the project builds successfully with the default signing configuration.

### Manual Verification
- Verify that the error `Keystore file ... not found` no longer appears during the build process.
