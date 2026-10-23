# Pixel Replica capture for Android

A native Java/Views Android app based on Wes Bos's linked-photo, camera-pose and comparison approach. The app captures individual objects and exports `pixel-object-capture/1` bundles. It does not run RoomPlan or claim to provide iPhone LiDAR on a Pixel.

## Install on Pixel 8

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

1. On the phone, open **Settings → About phone** and tap **Build number** seven times. Enable **USB debugging** in **Settings → System → Developer options**.
2. Connect the phone to the Windows laptop with a data-capable USB cable. Accept the phone's debugging prompt.
3. In PowerShell, run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.pixelreplica.capture/.MainActivity
```

Alternatively, copy the APK to the phone, open it in Files and allow installation from that source. This is a development build, not a Play Store release.

## First LEGO capture

1. Use bright diffuse light and a fixed background with visible texture. Keep the car stationary. Keep digital zoom at 1×. Avoid flash. A neutral colour card and measured ruler help the later evidence check.
2. Enter a name, then choose **Start new capture**. Grant camera permission and accept Google Play Services for AR installation/update if requested.
3. Read the saved-stream resolution in the viewfinder. Normal capture uses original Camera2 JPEGs, with the largest supported configuration attempted up to 16 MP. **CPU FALLBACK** is explicitly lower-resolution. It is useful for a compatibility test, not proof of fine-detail fidelity.
4. Fill the blue framing guide with the car while retaining some surrounding background. Move the phone around the car in low, middle and high overlapping passes. Stop briefly for each **Capture**. The guide is not an object mask or coverage map.
5. **Auto when steady** is optional. It requires tracking, several steady frames, usable preview sharpness/clipping and at least 2.5 seconds between attempts. It does not certify photo quality. Manual capture preserves difficult observations with explicit evidence.
6. After metering warms up, **Request fixed still white balance** attempts to reuse metered gains and colour transformation. This is a request. Actual returned AE/AWB state is saved; the UI does not claim a confirmed lock. Diffuse lighting and a colour reference remain necessary.
7. Select **Holdout photo** for a few comparison views. These photos are excluded from reconstruction. Auto capture pauses for holdout selection so deliberate check views stay separate.
8. Use **New pass** after turning over or moving the car. Separate passes have independent coordinate epochs and must be aligned on the laptop. Do not use an object turntable within one epoch.
9. Choose **Review**, inspect the latest thumbnails/full photos, and add physical measurements in millimetres. Use one `scale` measurement and independent `check` measurements, with realistic uncertainty. The laptop must associate each measurement with matching model points.

## Transfer to the laptop

1. On the review screen choose **Save capture ZIP…**. In Android's file picker select **Downloads**, then save. **Share capture ZIP…** is available for a transfer app you choose.
2. Connect by USB. On the phone choose **File transfer** in the USB notification. In Windows File Explorer open **Pixel 8 → Internal shared storage → Download** and copy the entire ZIP to the laptop.
3. Use the repository's `tools/object-replica` importer. Keep the original ZIP. It contains original JPEG bytes, timestamps, poses, actual camera metadata, measurements and SHA-256 checksums. No ZIP is uploaded by this app. It requests no internet permission, API key, account or server.

The laptop reconstructs the mesh and textures using photogrammetry. The LLM controls tools and reviews source/model comparisons and warnings. An LLM is not the geometric reconstruction engine.

Each export is an immutable snapshot. Its UUID is derived from the source payload hashes: unchanged exports reuse an ID; additional photos or measurements create a new one. The export manifest retains the original phone session UUID in `sourceSessionId`. Earlier snapshots remain unchanged.

## Build and local checks

Requires JDK 17, Android SDK platform 36, and network access for initial Gradle dependencies. The project pins Android Gradle Plugin 9.0.1, Gradle 9.3.1 and ARCore 1.48.0. It targets API 36 and supports API 29 and above.

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

- No Pixel 8 is attached in the development environment. Compilation and unit tests do not prove JPEG+ARCore stream compatibility, autofocus, colour controls or fine-detail capture on the real phone.
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
