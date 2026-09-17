# Implementation Plan - Fix Missing Debug Keystore Error

The build is failing because `debugConfig` in `app/build.gradle.kts` is configured to use a `debug.keystore` file in the project root which does not exist.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/NPH/Downloads/TRO-LI-TRAI-CAY/app/build.gradle.kts)

- Remove the custom `debugConfig` from `signingConfigs`.
- Update the `debug` build type to use the default `signingConfigs.getByName("debug")` (or simply remove the manual `signingConfig` assignment, as `debug` build type uses the default `debug` signing config by default).

Given that `debugConfig` was using the default Android debug credentials (`android`/`androiddebugkey`), removing it and using the system default is the most robust solution.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the build succeeds and the app is signed with the default debug key.
