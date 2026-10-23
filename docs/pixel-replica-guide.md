# Capture an object with Pixel Replica

Pixel Replica adds Android object capture and a local Windows reconstruction workflow to Wes Bos's 3D Model Anything project. It preserves the original photographs and records camera evidence. COLMAP builds geometry from overlapping photographs. A coding agent reviews evidence and guides corrections.

## Install and start

Install the debug APK on the Pixel 8. USB debugging is needed for an `adb install` from a computer. Alternatively copy the APK to the phone and open it with Android's installer. The debug build is for local testing.

Double-click `Start-PixelReplica.cmd` on the laptop. The local review page opens at `http://127.0.0.1:5190`. It uses Python 3.12 with NumPy and Pillow. The source build uses Node.js and `npm ci`. This task includes an installed portable COLMAP 4.2.1 CUDA engine in its working dependencies folder. Set `COLMAP_PATH` to `COLMAP.bat` if you move the project. [COLMAP installation](https://colmap.github.io/install.html) describes official portable Windows releases.

## Photograph the car

1. Put the car on a stable surface in diffuse, consistent light. Include a ruler or measured reference. Keep a separate physical measurement for a later check.
2. Start a capture and name the object. Keep the car still while you move the phone.
3. Start with roughly 60–100 overlapping views around the car at low, middle and high angles. Pause briefly for sharp frames. Take close views of studs, lettering, wheels and graphics. Review the actual saved resolution. More photos do not compensate for glare, blur or missing views.
4. Turn on **Holdout photo** for separate validation views. These photographs stay out of geometry reconstruction and texture generation.
5. If you turn the car over, start a new object pass. The laptop processes one pass at a time. Automatic alignment of different object orientations is not implemented, so this version does not yet produce a single complete top-and-underside model.
6. Save the capture. Add physical measurements in millimetres. These notes do not establish model scale until you identify matching endpoints on the mesh.

Glossy plastic, dark surfaces, transparent parts and repeated studs can cause missing or incorrect reconstruction. The app's tracking and sharpness indicators are capture feedback. They do not certify complete surface coverage.

## Move the data to the laptop

1. Tap **Save capture ZIP…** on the phone and save the file in Downloads.
2. Connect the phone by USB. Unlock it and choose **File transfer** in the USB notification.
3. Copy the ZIP from the phone's Download folder to the laptop.
4. Open Pixel Replica and choose **Import scan**. Select the ZIP.

The ZIP contains original JPEGs, camera evidence, measurements and checksums. The importer validates the archive and preserves originals under ignored `data/object-captures/`. It creates a new folder for every reconstruction. The app has no LLM keys or automatic photo upload.

## Reconstruct and compare

Select an object pass and choose **Reconstruct object**. Keep the laptop powered. The engine extracts and matches features, estimates cameras, builds depth maps, fuses points, builds a surface and creates a photographic texture. The page reports failures and missing views. It does not substitute an invented model.

Inspect the model with **Orbit**. Use **Geometry only** to check shape without the photographic texture. Select a source photograph and choose **Match photo view**. This comparison uses a lens-corrected derivative and its recovered camera. The original remains available in orbit mode.

To establish scale, select two endpoints on the model. Enter the corresponding physical distance and choose **Set scale**. Add a different distance with **Check size**. The check reports dimensional error. It does not adjust the scale. A single scale measurement is not an independent accuracy check.

**Save scaled GLB** preserves the photographic texture and stores scale in metres for later editing. Dense exports are large and currently slow; the synthetic test produced a 63 MB file and its browser download took about 31 minutes. Export optimisation is still needed. The model remains available for review while it prepares.

Surface reconstruction uses inferred geometry between observations. The pipeline records that Poisson surface reconstruction can fill unseen regions. Uncontrolled photographs do not establish calibrated colour or material gloss. No software-only check proves that a print will match the real object.

## Give the evidence to a coding agent

Choose **Prepare agent packet**. Open the generated prompt and contact sheet. In Codex, use this project folder and ask the agent to read the packet's prompt. The agent can inspect local files, run reconstruction tools, compare the model with images and suggest targeted recapture.

The engine combines photographs geometrically. The LLM helps operate and review that process. It must preserve the originals and label measured, reconstructed and uncertain details. An agent packet does not automatically send your photos to a cloud API. Explicitly opening images in an online agent shares those selected images with that service.

## Current verification boundary

An APK build, unit tests and synthetic reconstruction can verify software behavior. They cannot establish real Pixel 8 image quality or LEGO replication accuracy. Those require a real phone capture, checked physical dimensions, repeat scans and comparison of source photographs with the final model. Editing and printing come after that capture check.
