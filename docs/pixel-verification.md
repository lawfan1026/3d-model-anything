# Pixel Replica local verification

Date: 2026-10-23. Base: Wes Bos's `3d-model-anything` at `31b2dc9`.

## Android baseline, version 0.1.0

- Debug APK built. Nine unit tests passed, with no failures or errors.
- Android lint: zero errors, nine warnings. See `apps/object-capture-android/VERIFICATION.md`.
- API 35 emulator: launch, saved-session review, physical measurement persistence, Android document export and FileProvider share chooser exercised.
- The exported synthetic ZIP was copied to the laptop and accepted by both the importer and the actual HTTP import endpoint. Hashes, holdout role and measurement note survived. Model scale remained unscaled until endpoints were selected.
- APK SHA-256: `77CD6F1C9815CF8698C9E123886B34A2EBD98F0F940E22070EB55CD17A910D07`.

The baseline emulator test used a labelled synthetic file payload. The following hardware checks were performed after that baseline.

## Pixel 8 hardware checks

The app installed and launched on a Pixel 8 running Android 17. Version 0.1.0 saved 21 original Camera2 JPEGs at 4080 × 3072. The phone exported an 87,755,869-byte ZIP to Downloads. USB transfer and the local importer validated the archive, all source checksums and both held-out observations. Updating the app with `adb install -r` preserved its saved sessions.

The owner confirmed moving or turning the car. Source photographs also show hands touching it and some visibly soft frames. This capture is retained as a camera and transfer test; it is not a valid fixed-scene reconstruction set for this pipeline. All final still results report passive autofocus scanning. The old metadata did not record the repeating AF mode, so it cannot prove a mode-switch cause.

Version 0.1.1 added a bounded wait for fresh, stable preview focus, reused the observed preview AF mode for the JPEG and saved preview/final focus evidence. Its 17 Android unit tests passed. On the phone, all three test shots had focused preview metadata but scanning metadata in their timestamp-matched final results. One inspected original looked reasonably sharp; focus-state metadata alone does not prove visible blur.

Version 0.1.2 adds one START request, a fresh confirmed lock, the JPEG with an idle trigger and one CANCEL request. Command results remain separate from timestamp-matched JPEG evidence. Cleanup rejects stale focus evidence before the next shot. The capture remains bounded to eight seconds. All 23 Android tests and lint passed, with zero lint errors and nine warnings. The new APK installed over the prior version and launched on the Pixel; the three-photo hardware focus check is pending.

Version 0.1.2 APK SHA-256: `1AE2B11C800104909BF9E28121ACE7E9BFCC89601CEBC3016A24C15EB228AB24`.

Original phone images and device identifiers remain local and are not included in the source PR.

## Reconstruction

The fixture generator rendered an original toy car in Blender. The imported ZIP contains 32 reconstruction photographs and four held-out photographs at 800 × 600. Ground-truth geometry stays outside the ZIP. Its artificial surface texture is easier to reconstruct than glossy LEGO plastic.

- Source ZIP SHA-256: `3e7c523aa79d2d76a7de08a780cddf31367abd03c449d93d635d2e32ea13cdc3`.
- Actual COLMAP 4.2.1 CUDA registered all 32 reconstruction views and fused 99,076 points.
- Default Poisson density trim removed every triangle. The surface check rejected the empty output despite the process exiting successfully. That failed run remains recorded.
- An explicit trim setting of five produced a surface with 317,065 vertices and 633,580 faces. Photographic texturing produced a 2048 × 1609 atlas. Meshing can infer surfaces and smooth details; these counts do not establish accuracy.
- A resumed run reused saved processing output. It did not repeat dense reconstruction. Earlier failures and their provenance remain in separate run folders.
- Four of four held-out photos were localized against frozen geometry using a copied database and images. They did not enter geometry reconstruction or texture generation. No bundle adjustment or dense reconstruction ran during validation. Production artifact hashes and existing sparse point coordinates/training camera poses remained unchanged.
- A Windows path-length failure and a native engine crash during selected-subset undistortion were caught. Short validation paths and full validation-model undistortion resolved the actual fixture run.

Final synthetic surface run: `20261023T070718-0e53c85c`, capture `8e976681-710f-5fb1-bc91-84eb42567872`.

## Review and remaining proof

The upstream room viewer and new object viewer build together. The backend suite passed all 33 tests, including the actual loopback HTTP boundary and hard failure for missing or unreadable protected output. The current Android suite passes 23 tests, for 56 automated tests across the two suites. The backend and viewers are unchanged by the focus repair. See `tools/object-replica/VERIFICATION.md` for the test scope and processing audit.

Independent source review found no remaining P1/P2 issues after corrections to project switching, asynchronous model loading, matched-camera control updates, camera callbacks, export snapshot identity and interrupted-job recovery. Browser inspection confirms a visible textured surface, a lens-corrected held-out photo pair and cleared model/picking controls when switching to a capture with no reconstruction.

The browser accepted two picked mesh endpoints and saved a labelled synthetic 48 mm scale annotation. GLB export produced 62,734,864 bytes with the same scale factor as that annotation. Its embedded texture pixels match the source atlas, allowing the expected vertical image flip. Export was exercised while the viewer showed geometry only, and the photographic texture remained in the file. This tests unit and texture transfer; approximate manual picks are not an independent dimensional validation. The dense-model download test took about 31 minutes in the in-app browser. Export speed remains a usability limitation; the UI now prevents duplicate exports and shows preparation status.

Real Pixel 8 JPEG stream compatibility and USB import are verified above. Reliable close focus across a full scan, motion blur, stable colour, repeated scans, independent physical dimensions and a complete top-and-underside model still require physical work. No test here establishes perfect LEGO replication or printer fidelity. This version has no automatic background removal or alignment between different object orientations.
