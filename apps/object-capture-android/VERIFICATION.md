# Local Android verification, 2026-10-23

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
