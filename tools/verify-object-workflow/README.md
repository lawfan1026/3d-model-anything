# Rendered reconstruction fixture

`generate_fixture.py` runs inside Blender 5.1 and renders an original toy car
from 32 overlapping camera positions and four withheld positions. It generates
real 800×600 JPEG pixels, a `pixel-object-capture/1` ZIP, exact synthetic camera
calibration, known dimensions, and a 25,000-point ground-truth PLY. Ground truth
stays outside the imported ZIP so reconstruction receives only capture evidence.

```powershell
& 'C:\Program Files\Blender Foundation\Blender 5.1\blender.exe' --background --factory-startup --python tools/verify-object-workflow/generate_fixture.py -- --output-folder work/synthetic/lego-like-fixture
```

The output folder must be new or empty. The adjacent ZIP must not exist. This
prevents replacing earlier evidence. To rerun, choose another output folder.
Generated photos, reconstructed models and logs belong under ignored `work/`.

The scene uses EEVEE with 16 requested samples, a fixed subject, two orbit heights,
stable procedural colour texture, original labels, and a plain floor. Pose matrices
are OpenGL camera-to-world, column-major, in metres with Y up. JPEG pixels are
unrotated. The camera calibration matches those pixels. All views share the
`upright-1` epoch. Four holdout photos must stay out of reconstruction.

Assertions check image count and dimensions, distinct camera positions, finite
orthonormal poses, file SHA-256 hashes, and identical hashes of ZIP payloads.
This does not assert that separate GPU renders are byte-identical.

Use the bumper span (132 mm) for scale; axle spacing (88 mm) and body width
(48 mm) are independent checks. The generated ground-truth JSON gives endpoint
coordinates. The lamps extend beyond the bumper faces, so whole-scene bounding
box length is deliberately not interchangeable with that scale observation.

This is a labelled synthetic software exercise. It does not prove phone-camera
quality, real LEGO feature capture, calibrated colour, printability or physical
accuracy. The artificially textured surface is easier than glossy untextured toys.
