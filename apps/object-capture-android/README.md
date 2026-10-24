# Pixel Replica capture for Android

A native Java/Views Android app based on Wes Bos's linked-photo, camera-pose and comparison approach. The app captures individual objects and exports `pixel-object-capture/1` bundles. It does not run RoomPlan or claim to provide iPhone LiDAR on a Pixel.

## Install on Pixel 8

The easiest path is the phone itself: see [docs/pixel-phone-testing.md](../../docs/pixel-phone-testing.md). GitHub Actions (`.github/workflows/android.yml`) builds every change and publishes `PixelReplica.apk` on the `pixel-replica-latest` release.

All builds are signed with the committed `debug.keystore` (password `android`), so APKs from GitHub, a cloud session or the laptop install over each other. It is a public test key; use a private key before distributing the app to anyone else. Version 0.1.x was signed with a laptop-local debug key and must be uninstalled once before installing 0.2.0.

From a computer with USB debugging enabled:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

## Guided capture (0.2.0)

1. Use bright diffuse light and a matte, textured surface under the object. Keep the object stationary and the zoom at 1×.
2. **Start scanning**. Grant camera permission and install Google Play Services for AR if asked. Move the phone slowly until tracking starts.
3. Walk around the object keeping it in the centre circle. **Auto capture** (on by default) takes a photo whenever the camera reaches a viewpoint the coverage dial still needs and holds steady briefly. Each photo uses 0.1.2's confirmed focus lock and full-resolution Camera2 JPEG.
4. The dial is the capture dome seen from above: outer ring low views (pitch −25° to 22°, 24 sectors), then middle (22°–47°, 24), top (47°–78°, 12) and the overhead centre. It turns with the camera so the current view is at the bottom; green is captured, yellow is the current sector. The banner says which lap to do next.
5. Coverage comes from the camera's viewing direction in ARCore's gravity-aligned world (`OrbitCoverage`). A new photo also needs 8° of separation from every earlier photo and 2 cm of camera movement, so turning the phone in place adds nothing.
6. If the camera restarts (screen off, app switch), the dial starts over because headings from different ARCore worlds cannot be compared. Photos are kept and stay in the same object pass.
7. **New pass** after moving or turning over the object. The pass is recorded as `objectPass`; the laptop reconstructs one pass at a time.
8. **Holdout photo** reserves check views; auto capture pauses while it is on. **Lock colour** requests fixed still white balance.
9. **Done** opens the review: thumbnails, physical measurements, ZIP export, and deleting a scan from the phone.

The Pixel 8 reports `PASSIVE_SCAN` in final still metadata even after a confirmed lock with a stationary lens at an unchanged distance (see Limits). The warning is still recorded in `quality.focusWarning`, but the capture screen only flags a photo as possibly soft when the lens moved or refocused.

## Transfer to the laptop

1. On the review screen choose **Save capture ZIP…**. In Android's file picker select **Downloads**, then save. **Share capture ZIP…** is available for a transfer app you choose.
2. Connect by USB. On the phone choose **File transfer** in the USB notification. In Windows File Explorer open **Pixel 8 → Internal shared storage → Download** and copy the entire ZIP to the laptop.
3. Use the repository's `tools/object-replica` importer. Keep the original ZIP. It contains original JPEG bytes, timestamps, poses, actual camera metadata, measurements and SHA-256 checksums. No ZIP is uploaded by this app. It requests no internet permission, API key, account or server.

The laptop reconstructs the mesh and textures using photogrammetry. The LLM controls tools and reviews source/model comparisons and warnings. An LLM is not the geometric reconstruction engine.

Each export is an immutable snapshot. Its UUID is derived from the source payload hashes: unchanged exports reuse an ID; additional photos or measurements create a new one. The export manifest retains the original phone session UUID in `sourceSessionId`. Earlier snapshots remain unchanged.

## Focus check notes from 0.1.2 (historical)

Keep the car and phone stationary for the first three photos. Leave **Auto when steady** and white-balance freeze off. Wait for each saved count. Inspect lettering, studs and tyre edges in the full-resolution images before taking more views. Then move only the phone to two overlapping viewpoints and repeat. Do not rotate the car.

The exported `quality.repeatingFocusAtTrigger` and `quality.repeatingFocusBeforeStill` record the observed repeating AF mode/state, lens state, focus distance, sensor timestamp and callback-arrival monotonic timestamp. `requestedControls.afMode` must match the observed mode; `controls.afMode`, `afState` and `lensState` report the actual still result. Timing gates use `elapsedRealtimeNanos` consistently and preserve sensor time separately.

`quality.focusLock` records the separate START result, confirmed lock result, CANCEL result/status and command timing. A lock must be from the START frame or later, with AF state 4 and lens state 0, before one JPEG is authorized. The still uses the same AF mode and an idle trigger. CANCEL is dispatched once after a started transaction completes, fails or times out; a fast next capture is blocked until cleanup completes and new focus evidence arrives. ARCore keeps ownership of repeating requests. Actual/requested capture intent and control mode are recorded to help diagnose any remaining differences between preview and still processing.

If focus changes, is scanning/unfocused, or has missing final evidence, the original JPEG is preserved with `quality.focusStatus: warning` and `quality.focusWarning`; the app asks for a retake. Such a photo is not established as sharp. Check those warnings before using a capture for reconstruction. A metadata pass also does not establish that autofocus selected the intended object or that every detail is sharp.

## Build and local checks

CI runs `./gradlew assembleDebug testDebugUnitTest` and a report-only `lintDebug`. Locally this requires JDK 17, Android SDK platform 36, and network access for initial Gradle dependencies. The project pins Android Gradle Plugin 9.0.1, Gradle 9.3.1 and ARCore 1.48.0. It targets API 36 and supports API 29 and above.

```powershell
# The helper uses an isolated copy of trusted Windows roots for certificate validation.
.\build.ps1

# Standard wrapper when your JDK already trusts your network certificate chain:
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --no-daemon
```

Unit tests exercise timestamp/epoch separation, tracking loss, quality/state gates, original-byte retention, ZIP checksums, holdout roles, physical measurements and recovery after a crash left an unindexed image. Test reports are under `app/build/reports/tests/`; Android lint is under `app/build/reports/`.

## Limits and physical verification still required

- Version 0.1.0 produced 21 original 4080 × 3072 JPEGs with timestamp-matched metadata on a real Pixel 8 running Android 17/API 37. Some were blurred and the car was moved. Version 0.1.1 then produced three stills with focused preview metadata but final still AF state `PASSIVE_SCAN`; one looked reasonably sharp. The exact cause remains unproven. Version 0.1.2's explicit focus lock needs a real-phone check. Compilation and unit tests do not prove autofocus, colour controls or fine-detail accuracy.
- JPEG images are paired to still results by sensor timestamp, not callback arrival. Poses are exact or nearest within 40 ms, otherwise explicitly unavailable. Nearest poses are evidence, not calibrated ground truth.
- JPEG and ARCore CPU frames can differ in crop/aspect/calibration. JPEG image intrinsics remain `unavailable`; CPU intrinsics and sensor calibration/crop are retained separately. Laptop photogrammetry estimates/refines the JPEG cameras. The app never scales CPU intrinsics and labels that exact JPEG calibration.
- Preview sharpness measures the centre 70 percent of the CPU frame; it does not prove all full-resolution details are sharp. No object segmentation, automatic surface coverage, raw depth or on-phone reconstruction is implemented in this slice.
- Every interrupted/restarted camera world or reoriented object gets a new epoch. Saved images and records survive restart. ARCore's world coordinate system does not survive by assumption.
- Smooth plastic, repeated LEGO geometry, reflections, transparent windows, occluded joints, tiny lettering and black tyres are difficult evidence. Colour photographs alone do not establish colour calibration or surface reflectance.
- This app captures and transfers evidence. A measured, independently checked reconstructed model of the real car is the next proof. Printing and physical reproduction remain separate tests.

## Sources

- [Wes Bos, 3d-model-anything](https://github.com/wesbos/3d-model-anything): upstream MIT project. Room-specific capture/reconstruction remains separate.
- [Google SharedCamera guide](https://developers.google.com/ar/develop/java/camera-sharing) and [API](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera): shared capture lifecycle and device stream limits.
- [ARCore camera configuration](https://developers.google.com/ar/develop/java/camera-configs): runtime configurations and autofocus.
- [Camera2 CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult): actual sensor metadata and timestamp semantics.
