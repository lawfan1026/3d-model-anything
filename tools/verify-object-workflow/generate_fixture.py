"""Render an original, labelled toy car as real multiview JPEG input.

Run with Blender, not normal Python. This is a software fixture and is not
evidence of Pixel 8 capture accuracy or real LEGO reproduction.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import sys
import uuid
import zipfile

import bpy
from mathutils import Matrix, Vector
import numpy as np


WORLD = Matrix.Rotation(-math.pi / 2, 4, "X")  # Blender Z-up -> contract Y-up.
SEED = 8192
WIDTH, HEIGHT = 800, 600


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def material(name, color, textured=True):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes.get("Principled BSDF")
    bsdf.inputs["Roughness"].default_value = 0.76
    if not textured:
        bsdf.inputs["Base Color"].default_value = (*color, 1)
        return mat
    # Stable object-bound colour variation supplies distinctive SIFT features.
    # All source geometry and graphics are generated here; no downloaded assets.
    coord = nodes.new("ShaderNodeTexCoord")
    noise = nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 27
    noise.inputs["Detail"].default_value = 2
    noise.inputs["Roughness"].default_value = 0.65
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.30
    ramp.color_ramp.elements[0].color = (*(max(0.006, c * 0.22) for c in color), 1)
    ramp.color_ramp.elements[1].position = 0.67
    ramp.color_ramp.elements[1].color = (*(min(1, c * 1.35 + 0.09) for c in color), 1)
    links.new(coord.outputs["Generated"], noise.inputs["Vector"])
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    return mat


def cube(name, loc, size, mat, bevel=0.0004):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    if bevel:
        mod = obj.modifiers.new("tiny moulded edge", "BEVEL")
        mod.width, mod.segments = bevel, 2
    return obj


def cylinder(name, loc, radius, depth, mat, rot=(0, 0, 0), vertices=40):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth,
                                      location=loc, rotation=rot)
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(mat)
    mod = obj.modifiers.new("edge", "BEVEL")
    mod.width, mod.segments = 0.00025, 2
    return obj


def label(name, text, loc, size, mat, rot=(0, 0, 0)):
    curve = bpy.data.curves.new(name, "FONT")
    curve.body, curve.size = text, size
    curve.align_x, curve.align_y = "CENTER", "CENTER"
    curve.extrude = 0.000035
    obj = bpy.data.objects.new(name, curve)
    bpy.context.collection.objects.link(obj)
    obj.location, obj.rotation_euler = loc, rot
    obj.data.materials.append(mat)
    return obj


def build_car():
    mats = {name: material(name, rgb) for name, rgb in {
        "paint red": (0.75, 0.045, 0.018), "paint blue": (0.02, 0.19, 0.65),
        "paint yellow": (0.85, 0.53, 0.025), "rubber": (0.07, 0.075, 0.085),
        "glass opaque": (0.09, 0.32, 0.42), "hub": (0.32, 0.35, 0.39),
    }.items()}
    white = material("graphic ivory", (0.92, 0.92, 0.85), False)
    black = material("graphic ink", (0.012, 0.016, 0.020), False)
    cube("chassis", (0, 0, 0.019), (0.120, 0.048, 0.014), mats["paint blue"])
    cube("lower body", (0, 0, 0.028), (0.120, 0.048, 0.010), mats["paint red"])
    cube("hood", (0.039, 0, 0.036), (0.040, 0.048, 0.009), mats["paint yellow"])
    cube("rear deck", (-0.043, 0, 0.036), (0.032, 0.048, 0.009), mats["paint blue"])
    cube("cab", (-0.004, 0, 0.044), (0.045, 0.042, 0.022), mats["glass opaque"])
    cube("roof", (-0.004, 0, 0.057), (0.049, 0.046, 0.005), mats["paint red"])
    for sign in (-1, 1):
        cube(f"bumper {sign}", (sign * 0.063, 0, 0.023), (0.006, 0.048, 0.008), mats["hub"])
        for y in (-0.016, 0.016):
            cube(f"lamp {sign} {y}", (sign * 0.0661, y, 0.029), (0.0005, 0.008, 0.005), white)
    for x in (-0.044, 0.044):
        for sign in (-1, 1):
            y = sign * 0.032
            cylinder(f"wheel {x} {sign}", (x, y, 0.015), 0.015, 0.009,
                     mats["rubber"], (math.pi / 2, 0, 0))
            cylinder(f"hub {x} {sign}", (x, sign * 0.0367, 0.015), 0.0087, 0.0007,
                     mats["hub"], (math.pi / 2, 0, 0))
            for k in range(12):
                a = k * math.tau / 12
                # Distinct coloured hub bolts break circular correspondence symmetry.
                cylinder(f"hub marker {x} {sign} {k}",
                         (x + 0.006 * math.sin(a), sign * 0.0372, 0.015 + 0.006 * math.cos(a)),
                         0.0007, 0.00045, white if k % 3 else mats["paint yellow"],
                         (math.pi / 2, 0, 0), vertices=12)
            for k in range(20):
                a = k * math.tau / 20
                tread = cube(f"tread {x} {sign} {k}",
                             (x + 0.0147 * math.sin(a), y, 0.015 + 0.0147 * math.cos(a)),
                             (0.0025, 0.008, 0.0008), mats["rubber"], 0.00015)
                tread.rotation_euler[1] = a
    for prefix, xs, z in (("hood stud", [0.028, 0.044, 0.056], 0.0417),
                          ("rear stud", [-0.054, -0.038], 0.0417),
                          ("roof stud", [-0.020, -0.004, 0.012], 0.0607)):
        for x in xs:
            for y in (-0.014, 0.002, 0.014):
                cylinder(f"{prefix} {x} {y}", (x, y, z), 0.0024, 0.0018,
                         mats["paint yellow"] if prefix.startswith("hood") else mats["paint red"])
    label("roof ID", "R8", (-0.004, -0.007, 0.0596), 0.010, white)
    label("hood ID", "42", (0.039, -0.007, 0.0407), 0.010, black)
    for sign, text in ((-1, "PIXEL 42"), (1, "REPLICA 8")):
        rot = (math.pi / 2, 0, 0) if sign == -1 else (math.pi / 2, 0, math.pi)
        label(f"side graphic {sign}", text, (0, sign * 0.02415, 0.0275), 0.0056, white, rot)
    # Unique small geometric graphics give broad, view-consistent feature coverage.
    rng = random.Random(SEED)
    for k in range(42):
        x, y = rng.uniform(-0.057, 0.057), rng.uniform(-0.021, 0.021)
        if -0.029 < x < 0.022:
            continue
        z = 0.0407
        cube(f"deck fleck {k}", (x, y, z),
             (rng.uniform(0.0006, 0.0018), rng.uniform(0.0005, 0.0015), 0.0001),
             white if k % 2 else black, 0)


def sample_ground_truth(path, samples=25000):
    deps = bpy.context.evaluated_depsgraph_get()
    verts, faces = [], []
    offset = 0
    for obj in bpy.context.scene.objects:
        if obj.type not in {"MESH", "FONT"} or obj.name == "floor":
            continue
        ev = obj.evaluated_get(deps)
        mesh = ev.to_mesh()
        mesh.calc_loop_triangles()
        for v in mesh.vertices:
            verts.append(tuple((WORLD @ obj.matrix_world @ v.co.to_4d()).to_3d()))
        faces.extend([tuple(offset + i for i in t.vertices) for t in mesh.loop_triangles])
        offset += len(mesh.vertices)
        ev.to_mesh_clear()
    v, f = np.asarray(verts), np.asarray(faces)
    tri = v[f]
    area = np.linalg.norm(np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]), axis=1)
    rng = np.random.default_rng(SEED)
    selected = tri[rng.choice(len(tri), samples, p=area / area.sum())]
    r = rng.random((samples, 2))
    r[r.sum(axis=1) > 1] = 1 - r[r.sum(axis=1) > 1]
    points = selected[:, 0] + r[:, :1] * (selected[:, 1] - selected[:, 0]) + r[:, 1:] * (selected[:, 2] - selected[:, 0])
    with path.open("w", encoding="ascii", newline="\n") as stream:
        stream.write(f"ply\nformat ascii 1.0\ncomment synthetic fixture, Y up, meters\nelement vertex {samples}\nproperty float x\nproperty float y\nproperty float z\nend_header\n")
        np.savetxt(stream, points, fmt="%.9f")
    return {"minMeters": v.min(axis=0).tolist(), "maxMeters": v.max(axis=0).tolist(),
            "sampleCount": samples, "triangleCount": len(f)}


def assert_fixture(folder, photos, expected_count):
    assert len(photos) == expected_count
    assert len(list((folder / "photos").glob("*.jpg"))) == expected_count
    assert len({p["file"] for p in photos}) == expected_count
    poses = []
    for record in photos:
        pose = np.array(record["cameraToWorld"]).reshape((4, 4), order="F")
        assert np.isfinite(pose).all()
        assert np.allclose(pose[3], [0, 0, 0, 1], atol=1e-7)
        assert np.allclose(pose[:3, :3].T @ pose[:3, :3], np.eye(3), atol=1e-6)
        assert abs(np.linalg.det(pose[:3, :3]) - 1) < 1e-6
        image = bpy.data.images.load(str(folder / record["file"]), check_existing=False)
        assert tuple(image.size) == (WIDTH, HEIGHT)
        bpy.data.images.remove(image)
        poses.append(tuple(pose[:3, 3]))
    assert len(set(poses)) == expected_count
    checksums = json.loads((folder / "checksums.json").read_text())
    for relative, expected in checksums.items():
        assert digest(folder / relative) == expected
    return {"imageCount": expected_count, "imageDimensions": [WIDTH, HEIGHT],
            "uniqueCameraPositions": len(set(poses)), "checksumCount": len(checksums),
            "poseRotationsOrthonormal": True, "payloadHashesRechecked": True}


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-folder", type=Path, required=True)
    parser.add_argument("--views-per-ring", type=int, default=16)
    parser.add_argument("--samples", type=int, default=16)
    args = parser.parse_args(argv)
    root = args.output_folder.resolve()
    if root.exists() and any(root.iterdir()):
        raise SystemExit(f"Refusing to replace nonempty fixture directory: {root}")
    root.mkdir(parents=True, exist_ok=True)
    folder = root / "capture"
    (folder / "photos").mkdir(parents=True)
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    build_car()
    gt = sample_ground_truth(root / "ground-truth.ply")
    floor_mat = material("plain neutral floor", (0.26, 0.28, 0.30), False)
    cube("floor", (0, 0, -0.0015), (0.50, 0.50, 0.002), floor_mat, 0)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    if hasattr(scene.eevee, "taa_render_samples"):
        scene.eevee.taa_render_samples = args.samples
    if hasattr(scene.eevee, "taa_samples"):
        scene.eevee.taa_samples = args.samples
    scene.render.resolution_x, scene.render.resolution_y = WIDTH, HEIGHT
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "JPEG"
    scene.render.image_settings.quality = 96
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "Standard"
    scene.world.color = (0.22, 0.22, 0.22)
    for name, loc, power, size in (("soft key", (0.15, -0.18, 0.30), 0.45, 0.25),
                                    ("soft fill", (-0.19, 0.13, 0.21), 0.30, 0.22)):
        data = bpy.data.lights.new(name, "AREA")
        data.energy, data.shape, data.size = power, "DISK", size
        light = bpy.data.objects.new(name, data)
        scene.collection.objects.link(light)
        light.location = loc
        light.rotation_euler = (Vector((0, 0, 0.025)) - light.location).to_track_quat("-Z", "Y").to_euler()
    data = bpy.data.cameras.new("calibrated synthetic camera")
    camera = bpy.data.objects.new("camera", data)
    scene.collection.objects.link(camera)
    scene.camera = camera
    data.lens, data.sensor_width, data.sensor_fit = 45, 36, "HORIZONTAL"
    data.clip_start, data.clip_end = 0.001, 10
    focal = WIDTH * data.lens / data.sensor_width
    intrinsics = {"fx": focal, "fy": focal, "cx": WIDTH / 2, "cy": HEIGHT / 2}
    poses = [(math.tau * i / args.views_per_ring, math.radians(height), "reconstruct")
             for height in (18, 43) for i in range(args.views_per_ring)]
    poses += [(math.radians(11 + i * 90), math.radians(31), "holdout") for i in range(4)]
    photos = []
    target = Vector((0, 0, 0.028))
    radius = 0.235
    for index, (angle, elev, role) in enumerate(poses, 1):
        camera.location = target + Vector((radius * math.cos(elev) * math.cos(angle),
                                           radius * math.cos(elev) * math.sin(angle),
                                           radius * math.sin(elev)))
        camera.rotation_euler = (target - camera.location).to_track_quat("-Z", "Y").to_euler()
        bpy.context.view_layer.update()
        relative = f"photos/{index:06d}.jpg"
        scene.render.filepath = str(folder / relative)
        bpy.ops.render.render(write_still=True)
        transform = WORLD @ camera.matrix_world
        photos.append({"id": f"{index:06d}", "file": relative, "epoch": "upright-1",
                       "timestampNs": str(index * 1_000_000_000), "width": WIDTH, "height": HEIGHT,
                       "role": role, "cameraToWorld": [float(transform[r][c]) for c in range(4) for r in range(4)],
                       "poseStatus": "matched", "poseDeltaNs": "0", "intrinsics": intrinsics,
                       "calibrationStatus": "calibrated", "cameraId": "synthetic-pinhole",
                       "quality": {"source": "synthetic rendered pixels; not a device quality test"},
                       "controls": {"synthetic": True},
                       "sensor": {"sensorOrientationDegrees": 0, "timestampSource": "synthetic-sequence"}})
        print(f"FIXTURE_FRAME {index}/{len(poses)} {relative}", flush=True)
    write_json(folder / "photos/photos.json", photos)
    measurements = [
        {"id": "bumper-span", "label": "Synthetic bumper outer faces, excludes protruding lamps", "valueMm": 132.0, "uncertaintyMm": 0.0, "role": "scale"},
        {"id": "axle-span", "label": "Synthetic front to rear axle centre spacing", "valueMm": 88.0, "uncertaintyMm": 0.0, "role": "check"},
        {"id": "body-width", "label": "Synthetic lower body side faces", "valueMm": 48.0, "uncertaintyMm": 0.0, "role": "check"},
    ]
    write_json(folder / "measurements.json", measurements)
    write_json(folder / "manifest.json", {
        "format": "pixel-object-capture/1", "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "pixel-replica/synthetic-car/" + digest(Path(__file__)))),
        "name": "Synthetic labelled toy car - software fixture",
        "createdAt": "2026-10-22T12:00:00Z", "device": {"manufacturer": "Synthetic", "model": "Blender pinhole camera", "blenderVersion": bpy.app.version_string},
        "status": "complete", "coordinateSystem": {"units": "meters", "cameraToWorld": "column-major-4x4", "cameraAxes": "+X right,+Y up,-Z forward", "worldAxes": "right-handed,+Y up"},
        "notes": "Original generated car geometry and graphics, not an actual LEGO scan. Known synthetic calibration. Shape and texture intentionally feature-rich. Floor is a neutral capture support. No underside capture. Holdouts excluded from reconstruction. Does not establish Pixel 8 or physical accuracy."})
    paths = sorted(p for p in folder.rglob("*") if p.is_file())
    write_json(folder / "checksums.json", {p.relative_to(folder).as_posix(): digest(p) for p in paths})
    assertions = assert_fixture(folder, photos, len(poses))
    archive_path = root.parent / f"{root.name}.zip"
    if archive_path.exists():
        raise SystemExit(f"Refusing to replace archive: {archive_path}")
    with zipfile.ZipFile(archive_path, "x", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(folder.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(folder).as_posix())
    with zipfile.ZipFile(archive_path) as archive:
        for relative, expected in json.loads((folder / "checksums.json").read_text()).items():
            assert hashlib.sha256(archive.read(relative)).hexdigest() == expected
    assertions["zipPayloadHashesMatchOriginals"] = True
    assertions["zipSha256"] = digest(archive_path)
    write_json(root / "assertions.json", assertions)
    write_json(root / "ground-truth.json", {"kind": "synthetic-only", "geometry": gt,
        "coordinates": "meters, X right, Y up, Z backward", "measurements": measurements,
        "scaleEndpoints": [[-0.066, 0.023, 0], [0.066, 0.023, 0]],
        "axleCheckEndpoints": [[-0.044, 0.015, 0.032], [0.044, 0.015, 0.032]],
        "bodyWidthCheckEndpoints": [[0, 0.028, -0.024], [0, 0.028, 0.024]],
        "sourceGeneratorSha256": digest(Path(__file__)), "assertions": assertions})
    print("FIXTURE_READY " + str(archive_path), flush=True)
    print(json.dumps(assertions), flush=True)


if __name__ == "__main__":
    main()
