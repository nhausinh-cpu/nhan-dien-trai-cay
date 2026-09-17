# Fix missing debug keystore error

The project is failing to build because the `debugConfig` signing configuration expects a `debug.keystore` file in the project root, which is missing.

## Proposed Changes

### [app module]

#### [MODIFY] [build.gradle.kts](file:///C:/Users/NPH/Downloads/TRO-LI-TRAI-CAY/app/build.gradle.kts)
- Remove the `debugConfig` block from `signingConfigs`.
- Remove the `signingConfig = signingConfigs.getByName("debugConfig")` line from the `debug` build type.

This will allow Gradle to automatically use the default debug keystore provided by the Android SDK, which is the standard behavior for debug builds.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the build succeeds.
