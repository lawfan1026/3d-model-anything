# Laptop pipeline verification

Verified locally on Windows with Python 3.12.14, NumPy 2.3.5, Pillow 12.3.0, COLMAP 4.2.1 (`bd1fcf6`, CUDA), and the laptop's NVIDIA RTX 4060. This is software evidence from rendered input photographs. It does not establish Pixel 8 camera compatibility, real LEGO capture fidelity, physical size accuracy or print fidelity.

## Automated boundary and workflow tests

`python -m unittest discover -s tools/object-replica -p "test_*.py" -v`: **33 passed**. Tests cover original-byte preservation; idempotent payload re-export with a different ZIP container; changed-payload collision; path traversal and Windows names; ZIP duplicates, links and expansion limits; complete SHA-256 verification; JPEG type/dimension checks; rigid camera transforms; held-out photo exclusion; epoch separation; engine failure; engine locking and abandoned job recovery; scale/check independence; source-to-camera projection; fixed geometry during holdout localization; short atomic names at a long Windows path; optional validation failure versus production mutation, deletion and unreadability; and actual loopback HTTP import/list/asset requests with Host/Origin/path rejection.

The local loopback test needed execution outside the network-restricted tool sandbox. No external server was contacted.

## Actual reconstruction

- Source: `lego-like-fixture-v2.zip`, SHA-256 `3e7c523aa79d2d76a7de08a780cddf31367abd03c449d93d635d2e32ea13cdc3`.
- Capture UUID: `8e976681-710f-5fb1-bc91-84eb42567872`.
- Completed run: `20261023T070718-0e53c85c`, in ignored `data/object-captures/<UUID>/runs/`.
- 36 synthetic 800 × 600 photographs: 32 reconstruction inputs and 4 holdouts.
- Actual COLMAP feature extraction, matching, sparse mapping, undistortion, CUDA stereo and geometric fusion ran. All 32 input photos registered. The fused cloud contains 99,076 points.
- Final surface: 317,065 vertices and 633,580 faces. Photographic texture atlas: 2048 × 1609 PNG, with per-face UVs in the PLY.
- All 39 original payload hashes and the preserved original ZIP hash were checked after processing. Copied reconstruction JPEG hashes equal the corresponding originals.
- The production feature database contains exactly the 32 reconstruction images; no held-out image appears in that database or reconstruction image mapping.
- All 4 holdouts localized against the frozen sparse model in a separate copied database. Sparse XYZ coordinates and pre-existing camera poses were checked unchanged. All 14 protected production artifact hashes were identical before and after validation and still match the current files.
- The final run remained unscaled at this verification checkpoint. Physical measurements linked to selected mesh endpoints are required for metric scale. Synthetic camera calibration was not used to assert physical accuracy.

The first Poisson invocation used the engine's default density trim of 10 and produced an empty mesh despite exit code 0. The surface validator rejected it. The normal pipeline now records an explicit density trim of 5; this is a sampling parameter, not a physical detail resolution. Separate derivative resume runs reused the fused cloud and then the valid surface, preserving failed attempts rather than repeating stereo or overwriting earlier evidence.

Holdout localization uses short derivative paths and undistorts the separate validation model in full. An earlier selected-image undistortion invocation exited with native error `0xC0000409`; that validation failure was retained. Holdout failure does not hide an otherwise valid surface. A production artifact mutation is a hard failure.

## Remaining proof

No actual Pixel 8 scan has been supplied. Close-focus performance, saved JPEG quality, scene tracking, gloss and transparent plastic, fine LEGO detail, colour calibration, occluded surfaces and independent dimensional errors require a real capture. Poisson meshing can smooth or infer surfaces. The background is retained until reviewed/cropped. Successful processing and a textured appearance do not prove a faithful physical replica.
