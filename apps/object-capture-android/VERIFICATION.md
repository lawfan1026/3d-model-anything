# Local Android verification, 2026-10-23

## Version 0.1.0 local baseline

Command: `./build.ps1` (Windows PowerShell).

- `assembleDebug`: passed; installable debug APK produced.
- `testDebugUnitTest`: 9 tests, 0 failures, 0 errors.
- `lintDebug`: passed; 0 errors, 9 warnings. Warnings cover newer dependency versions, portrait orientation and English prototype text. No lint errors were suppressed.
- APK SHA-256: `77CD6F1C9815CF8698C9E123886B34A2EBD98F0F940E22070EB55CD17A910D07`.

The APK was installed and launched in the existing API 35 Android emulator in hidden read-only mode. Home layout was inspected. A clearly labelled **SYNTHETIC emulator transfer test** session was seeded through debug access to exercise review and transfer without claiming a real scan.

Verified native flow:

1. Open saved synthetic session; review reports one holdout photo and its thumbnail.
2. Add `Overall length`, `145.2 ± 0.5 mm`, role `scale`; review displays the saved observation.
3. Choose **Save capture ZIP…**; Android document picker opens in Downloads.
4. Save, wait for writing to finish, and copy the ZIP from emulator Downloads to the laptop.
5. The actual desktop importer accepts the ZIP, validates its checksums, retains the holdout role and physical measurement, and reports metric scale as `unscaled` because model endpoints have not been selected.
6. Choose **Share capture ZIP…**; Android's share chooser resolves the ZIP through FileProvider. No recipient or service was selected.

Imported snapshot UUID: `42fbb8ae-5707-39c1-85b0-8256f8abdba3`. Phone source-session UUID remains `00000000-0000-4000-8000-000000000001`. Export size: 213,948 bytes. The test fixture image is a screenshot used only as a synthetic file-transfer payload.

Intermediate evidence is in ignored `.gradle/`: `emulator-home.png`, `emulator-review.png`, `emulator-save.png`, `emulator-share.png`, corresponding UI XML, `emulator-export.zip`, and the import result under `import-proof/`.

This proves local build, launch, measurement persistence, document export, share URI and cross-component import. It does not prove Pixel 8 JPEG+ARCore compatibility, real camera capture, close focus, colour stability, geometric accuracy, LEGO reconstruction or a printed replica. The owned emulator was stopped after the check.

## Version 0.1.0 first real Pixel 8 capture

The app was subsequently installed and used on the owner's Pixel 8 running Android 17/API 37. It saved and exported 21 original 4080 × 3072 JPEGs; all still controls had timestamp-matched metadata. Every still reported AF state `PASSIVE_SCAN`, and some full-resolution photos were visibly blurred. The object was moved during the set. This verifies the tested capture/export compatibility, while focus quality, shared-world geometry and replica fidelity remain unproven.

## Version 0.1.1 autofocus correction

Command: `./build.ps1`. `assembleDebug`, all 17 unit tests (8 new focus-policy tests) and `lintDebug` pass. Lint reports 0 errors and 9 warnings.

APK SHA-256: `396E61FB79875F57727ECEE9EF094F0B193A0B5A4CB24FB0B4EF659DF484D73A`.

Version code is 2, version name is 0.1.1, application ID remains `com.pixelreplica.capture`, and the default debug signing configuration is unchanged. No uninstall, storage migration or change to old capture records is included.

JPEG capture now waits briefly for fresh, focused repeating metadata and stable focus distance, then reuses the actual repeating AF mode with an idle trigger. Callback freshness and settling use one host monotonic clock. Exports retain pre-trigger/repeating focus evidence and actual still mode/state/lens metadata. A final scanning/unfocused/missing-focus result preserves the JPEG with an explicit retake warning. No START/CANCEL sequence or ARCore repeating-ownership change is included.

The physical test used three steady photos with auto capture and white-balance freeze off. The repeating/requested/still AF comparison and full-resolution inspection are recorded below.

### Subsequent 0.1.1 physical result

The parent tested three stills. Repeating metadata was AF mode 3, state 2, lens state 0 and a stable focus window; the requested still AF mode was also 3. All three final still results reported mode 3, state 1 and lens state 0, triggering the explicit warnings. One inspected photo looked reasonably sharp. This disproves a simple AF-mode mismatch explanation for those three stills. The exact preview/still state transition cause remains unproven, and visual sharpness remains a separate observation.

## Version 0.1.2 explicit autofocus lock

Command: `./build.ps1`. `assembleDebug`, all 23 unit tests (6 new lock/cleanup sequence tests) and `lintDebug` pass. Lint reports 0 errors and 9 warnings.

APK SHA-256: `1AE2B11C800104909BF9E28121ACE7E9BFCC89601CEBC3016A24C15EB228AB24`. Version code 3, version name 0.1.2. Signer certificate SHA-256 remains `362545dfcdc5ac982ee4dc12b65a2e288b1f0d41c71e1d351196242773e54d01`.

The capture sequence now sends one START in the observed AF mode, waits for a fresh matching `FOCUSED_LOCKED` result with stationary lens from the START frame or later, captures one JPEG with an idle trigger, then claims one CANCEL. START/CANCEL use separate tags and callbacks and never assign JPEG metadata. Cleanup blocks a fast next capture, clears old readiness and filters evidence at or before the CANCEL result. No ARCore repeating request is replaced. The overall capture deadline remains eight seconds. Actual/requested capture intent and control mode are recorded for diagnosis.

The parent installed version 0.1.2 over the prior version on the Pixel 8 and verified version code 3 and successful launch. The three-photo physical focus check is pending. The implementation worker ran no USB or reconstruction commands; the parent owns installation and real-phone checks.
