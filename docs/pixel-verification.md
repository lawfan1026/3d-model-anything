# Pixel Replica local verification

Date: 2026-10-23. Base: Wes Bos's `3d-model-anything` at `31b2dc9`.

## Android

- Debug APK built. Nine unit tests passed, with no failures or errors.
- Android lint: zero errors, nine warnings. See `apps/object-capture-android/VERIFICATION.md`.
- API 35 emulator: launch, saved-session review, physical measurement persistence, Android document export and FileProvider share chooser exercised.
- The exported synthetic ZIP was copied to the laptop and accepted by both the importer and the actual HTTP import endpoint. Hashes, holdout role and measurement note survived. Model scale remained unscaled until endpoints were selected.
- APK SHA-256: `77CD6F1C9815CF8698C9E123886B34A2EBD98F0F940E22070EB55CD17A910D07`.

No Pixel was connected. The emulator used a labelled synthetic file payload. It does not prove real camera capture.

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

The upstream room viewer and new object viewer build together. The final backend suite passes all 33 tests, including the actual loopback HTTP boundary and hard failure for missing or unreadable protected output. Together with the nine Android tests, 42 automated tests pass. See `tools/object-replica/VERIFICATION.md` for the test scope and processing audit.

Independent source review found no remaining P1/P2 issues after corrections to project switching, asynchronous model loading, matched-camera control updates, camera callbacks, export snapshot identity and interrupted-job recovery. Browser inspection confirms a visible textured surface, a lens-corrected held-out photo pair and cleared model/picking controls when switching to a capture with no reconstruction.

The browser accepted two picked mesh endpoints and saved a labelled synthetic 48 mm scale annotation. GLB export produced 62,734,864 bytes with the same scale factor as that annotation. Its embedded texture pixels match the source atlas, allowing the expected vertical image flip. Export was exercised while the viewer showed geometry only, and the photographic texture remained in the file. This tests unit and texture transfer; approximate manual picks are not an independent dimensional validation. The dense-model download test took about 31 minutes in the in-app browser. Export speed remains a usability limitation; the UI now prevents duplicate exports and shows preparation status.

Real Pixel 8 stream compatibility, close focus, motion blur, stable colour, repeated scans, independent physical dimensions and a complete top-and-underside model still require physical work. No test here establishes perfect LEGO replication or printer fidelity. This version has no automatic background removal or alignment between different object orientations.
