# Pixel object capture, version 1

This adapter extends Wes Bos's capture-and-compare approach for individual objects. It does not emit a RoomPlan house bundle. The format identifier is `pixel-object-capture/1`.

## ZIP contents

- `manifest.json`: `format`, UUID `id`, `name`, ISO `createdAt`, `device` object, `status` (`complete` or `interrupted`), `coordinateSystem`, optional `notes`.
- `photos/photos.json`: array of observations, defined below.
- `photos/NNNNNN.jpg`: original, unrotated sensor pixels. Preserve JPEG bytes and EXIF. Do not rotate pixels without updating calibration.
- `measurements.json`: array of `{id,label,valueMm,uncertaintyMm,role}`. Role is `scale` or `check`. These are physical observations, not established mesh dimensions.
- `checksums.json`: map of relative file paths to SHA-256 hex. Excludes itself. Required on Android export. Validate all supplied hashes on laptop import.
- Optional `depth/`: raw depth and confidence, with explicit encoding and calibration in photo records. Depth is supporting evidence.

Android exports are immutable snapshots. The snapshot `id` is derived from the source payload hashes. An unchanged export keeps its ID; changed photos or measurements create a new one. `sourceSessionId` retains the phone session UUID. A different ZIP container with the same verified payload is an idempotent import. Changed payload under an existing snapshot ID must never replace its originals.

Android 0.2.0 and later add `objectPass` to every observation (`pass-1`, `pass-2`, …) and `currentObjectPass` to the manifest. An object pass is one unmoved placement of the object; it changes only when the person taps **New pass** after moving or turning the object over. It does not change when the camera restarts, so photogrammetry reconstructs one object pass even if it spans several tracking epochs. Readers fall back to `epoch` when `objectPass` is absent.

Manifest coordinate system is `{units:"meters", cameraToWorld:"column-major-4x4", cameraAxes:"+X right,+Y up,-Z forward", worldAxes:"right-handed,+Y up"}`. The camera matrix uses the physical ARCore camera, never display-oriented pose. Every observation has an `epoch` string. A restarted tracking world or reoriented object starts a new epoch. Do not combine different epochs as one measured world.

## Observation

Required keys:

```json
{
  "id": "000001",
  "file": "photos/000001.jpg",
  "epoch": "upright-1",
  "timestampNs": "123456789012345",
  "width": 4032,
  "height": 3024,
  "role": "reconstruct",
  "cameraToWorld": null,
  "poseStatus": "unavailable",
  "poseDeltaNs": null,
  "intrinsics": null,
  "calibrationStatus": "unavailable",
  "cameraId": "0",
  "quality": {},
  "controls": {}
}
```

`role` is `reconstruct` or `holdout`. Holdout photos must not enter reconstruction. `cameraToWorld` is null or 16 finite values. `poseStatus` is `matched`, `nearest`, or `unavailable`. `poseDeltaNs` describes actual timestamp separation, not callback delay. Nanosecond timestamps are strings to prevent JavaScript precision loss.

`intrinsics` is null or `{fx,fy,cx,cy}` in pixels for this image. `calibrationStatus` is `calibrated`, `approximate`, or `unavailable`. Camera2 JPEG dimensions may differ from ARCore CPU dimensions and crop. Do not silently scale CPU intrinsics and claim exact JPEG calibration. Preserve `cpuIntrinsics` and camera metadata as additional evidence when JPEG calibration is unavailable. Photogrammetry estimates/refines its own cameras.

Optional `sensor` records active-array coordinates, crop rectangle, distortion, physical camera ID, sensor orientation, and timestamp source. `controls` records actual capture results such as exposure time, ISO, focus distance, AWB/AE states and gains. Requested settings are separate from actual settings. `quality` records measured sharpness, clipping, motion, and tracking state when available. Missing quality data is not a pass.

Android 0.1.1 and later also record `quality.repeatingFocusAtTrigger` and `quality.repeatingFocusBeforeStill`, including the preview AF mode/state, lens state and focus-distance settling evidence. Sensor timestamps and host elapsed-realtime callback timestamps are separate fields; do not subtract them as though they share a time origin. Android 0.1.2 adds `quality.focusLock` with separate START, confirmed-lock and CANCEL evidence. Command results do not replace the final JPEG's timestamp-matched `controls`. `requestedControls` records the JPEG request, including AF mode/trigger, control mode and capture intent. `quality.focusStatus` and optional `focusWarning` report metadata checks; they do not certify visible sharpness or object detail. A warning preserves the original JPEG for review.

## Reconstruction result and scale

The original ZIP and imported originals remain immutable. Each reconstruction uses a new run directory. The result records registered images, skipped/held-out images, command logs, camera calibration, mesh, texture, warnings, and engine version. A failed command must not produce a success state.

Initial photogrammetry coordinates have unknown scale. Metric status starts `unscaled`. After mapping, the laptop fits a similarity transform between recovered camera centres and ARCore camera centres, separately for each tracking epoch, and stores `run.phoneScale`. A `good` fit (residual under 10% of camera spread, epochs agreeing within 5%) gives scale status `phone-estimate`; this is an estimate with typical error of a few percent. A measured distance between two selected mesh points establishes a scale factor. Independent `check` measurements report residual errors without changing scale. An ARCore trajectory fit, if added, is approximate until independently checked.

Colour matching from uncontrolled photographs is not colour calibration. Smoothing, inferred surfaces and hole filling must be recorded. No result is described as perfect or physically verified from software checks alone.
