#!/usr/bin/env python3
"""Local, evidence-preserving object photogrammetry. See API.md for the public API."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import mimetypes
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlparse
from uuid import UUID, uuid4
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageOps

REPO = Path(__file__).resolve().parents[2]
DEFAULT_STORE = REPO / "data" / "object-captures"
FORMAT = "pixel-object-capture/1"
MAX_ARCHIVE = 2 * 1024**3
MAX_EXPANDED = 8 * 1024**3
MAX_ENTRY = 256 * 1024**2
MAX_FILES = 12000
MAX_PIXELS = 100_000_000
MAX_JSON = 24 * 1024**2
COORDINATES = {"units": "meters", "cameraToWorld": "column-major-4x4",
               "cameraAxes": "+X right,+Y up,-Z forward", "worldAxes": "right-handed,+Y up"}
RUN_STATES = {"queued", "running", "succeeded", "failed"}
ENGINE_COMMANDS = ("feature_extractor", "exhaustive_matcher", "mapper", "image_undistorter",
                   "patch_match_stereo", "stereo_fusion", "poisson_mesher", "mesh_texturer", "model_converter", "image_registrator")


class ReplicaError(ValueError):
    """Expected, actionable boundary or reconstruction failure."""


class ArtifactMutationError(ReplicaError):
    """Validation changed production evidence and cannot be treated as optional."""


class EngineLock:
    """One engine job per store, including jobs started from a second CLI process."""
    def __init__(self, store):
        Path(store).mkdir(parents=True, exist_ok=True)
        self.file = (Path(store) / ".engine.lock").open("a+b")
        self.file.seek(0, os.SEEK_END)
        if self.file.tell() == 0:
            self.file.write(b"0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            self.file.close()
            raise ReplicaError("A reconstruction already owns the local engine. Wait for that job to finish.") from exc

    def close(self):
        if self.file.closed:
            return
        self.file.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
        self.file.close()


def now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024**2), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    path = Path(path)
    if path.stat().st_size > MAX_JSON:
        raise ReplicaError(f"JSON exceeds {MAX_JSON} bytes: {path.name}")
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_json_keys,
                          parse_constant=lambda value: (_ for _ in ()).throw(ReplicaError(f"Invalid JSON number {value}")))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ReplicaError(f"Invalid JSON: {path.name}") from exc


def unique_json_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReplicaError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{uuid4().hex[:8]}.tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def finite(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def safe_relative(value):
    if not isinstance(value, str) or not value or any(character in value for character in '\\:*?"<>|') or any(ord(character) < 32 for character in value):
        raise ReplicaError("Unsafe relative file path")
    parts = PurePosixPath(value).parts
    if value.startswith("/") or any(part in (".", "..") for part in parts) or "//" in value:
        raise ReplicaError("Unsafe relative file path")
    # Reject Windows aliases and trailing dots/spaces even on other hosts.
    for part in parts:
        if part.endswith((".", " ")) or re.match(r"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", part, re.I):
            raise ReplicaError("Unsafe Windows file name")
    return PurePosixPath(value).as_posix()


def inside(root, relative):
    relative = safe_relative(relative)
    root = Path(root).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root):
        raise ReplicaError("File is outside project")
    return path


def project_path(store, project_id):
    try:
        canonical = str(UUID(str(project_id)))
    except (ValueError, AttributeError) as exc:
        raise ReplicaError("Project ID must be a UUID") from exc
    path = Path(store) / canonical
    if not (path / "project.json").is_file():
        raise FileNotFoundError("Project not found")
    return path


def validate_pose(value):
    if value is None:
        return
    if not isinstance(value, list) or len(value) != 16 or not all(finite(x) for x in value):
        raise ReplicaError("Camera matrix must contain 16 finite values")
    matrix = np.array(value).reshape((4, 4), order="F")
    rotation = matrix[:3, :3]
    if not np.allclose(matrix[3], [0, 0, 0, 1], atol=1e-4) or not np.allclose(rotation.T @ rotation, np.eye(3), atol=0.02) or not math.isclose(np.linalg.det(rotation), 1, abs_tol=0.02):
        raise ReplicaError("Camera matrix is not a finite right-handed rigid transform")


def validate_capture(root):
    root = Path(root)
    manifest = read_json(root / "manifest.json")
    if not isinstance(manifest, dict) or manifest.get("format") != FORMAT:
        raise ReplicaError(f"Expected format {FORMAT}")
    try:
        project_id = str(UUID(manifest["id"]))
    except (KeyError, ValueError, TypeError, AttributeError) as exc:
        raise ReplicaError("Capture ID must be a UUID") from exc
    if not isinstance(manifest.get("name"), str) or not 1 <= len(manifest["name"]) <= 200:
        raise ReplicaError("Capture name must contain 1–200 characters")
    if not isinstance(manifest.get("createdAt"), str) or not isinstance(manifest.get("device"), dict):
        raise ReplicaError("Manifest needs createdAt and device")
    if manifest.get("status") not in ("complete", "interrupted") or manifest.get("coordinateSystem") != COORDINATES:
        raise ReplicaError("Invalid status or coordinate convention")
    photos = read_json(root / "photos" / "photos.json")
    measurements = read_json(root / "measurements.json")
    checksums = read_json(root / "checksums.json")
    if not isinstance(photos, list) or not 1 <= len(photos) <= MAX_FILES - 4:
        raise ReplicaError("Capture needs a non-empty photo array")
    if not isinstance(checksums, dict):
        raise ReplicaError("checksums.json must map paths to SHA-256")
    actual_files = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()} - {"checksums.json"}
    if set(checksums) != actual_files:
        raise ReplicaError("Checksums must cover every original file, excluding checksums.json")
    for relative, expected in checksums.items():
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
            raise ReplicaError("Invalid SHA-256 checksum")
        if sha256(inside(root, relative)).lower() != expected.lower():
            raise ReplicaError(f"Checksum mismatch: {relative}")
    ids, files = set(), set()
    required = {"id", "file", "epoch", "timestampNs", "width", "height", "role", "cameraToWorld", "poseStatus", "poseDeltaNs", "intrinsics", "calibrationStatus", "cameraId", "quality", "controls"}
    for observation in photos:
        if not isinstance(observation, dict) or not required.issubset(observation):
            raise ReplicaError("Photo observation is missing required fields")
        photo_id = observation["id"]
        if not isinstance(photo_id, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", photo_id) or photo_id in ids:
            raise ReplicaError("Photo IDs must be safe and unique")
        relative = safe_relative(observation["file"])
        if not relative.startswith("photos/") or not relative.lower().endswith((".jpg", ".jpeg")) or relative.casefold() in files:
            raise ReplicaError("Photo files must be unique JPEG paths under photos/")
        ids.add(photo_id)
        files.add(relative.casefold())
        if not isinstance(observation["epoch"], str) or not 1 <= len(observation["epoch"]) <= 100:
            raise ReplicaError("Photo needs a coordinate epoch")
        if observation["role"] not in ("reconstruct", "holdout"):
            raise ReplicaError("Photo role must be reconstruct or holdout")
        if not isinstance(observation["timestampNs"], str) or not re.fullmatch(r"\d{1,30}", observation["timestampNs"]):
            raise ReplicaError("Photo timestampNs must be an integer string")
        if observation["poseStatus"] not in ("matched", "nearest", "unavailable") or observation["calibrationStatus"] not in ("calibrated", "approximate", "unavailable"):
            raise ReplicaError("Invalid pose or calibration status")
        if not isinstance(observation["cameraId"], str) or not isinstance(observation["quality"], dict) or not isinstance(observation["controls"], dict):
            raise ReplicaError("Invalid camera/quality/control evidence")
        delta = observation["poseDeltaNs"]
        if delta is not None and not (isinstance(delta, str) and re.fullmatch(r"-?\d{1,30}", delta)) and not (isinstance(delta, int) and not isinstance(delta, bool)):
            raise ReplicaError("Invalid pose timestamp separation")
        validate_pose(observation["cameraToWorld"])
        if observation["poseStatus"] != "unavailable" and observation["cameraToWorld"] is None:
            raise ReplicaError("A matched/nearest pose requires a camera matrix")
        calibration = observation["intrinsics"]
        if calibration is not None and (not isinstance(calibration, dict) or not all(finite(calibration.get(k)) for k in ("fx", "fy", "cx", "cy")) or calibration["fx"] <= 0 or calibration["fy"] <= 0):
            raise ReplicaError("Invalid photo intrinsics")
        if observation["calibrationStatus"] == "calibrated" and calibration is None:
            raise ReplicaError("Calibrated photo requires its own intrinsics")
        width, height = observation["width"], observation["height"]
        if not all(isinstance(v, int) and not isinstance(v, bool) and v > 0 for v in (width, height)) or width * height > MAX_PIXELS:
            raise ReplicaError("Invalid or excessive photo dimensions")
        try:
            with Image.open(inside(root, relative)) as image:
                if image.format != "JPEG" or image.size != (width, height):
                    raise ReplicaError(f"JPEG dimensions do not match observation {photo_id}")
                image.verify()
        except (OSError, Image.DecompressionBombError) as exc:
            raise ReplicaError(f"Invalid JPEG: {relative}") from exc
    if not isinstance(measurements, list):
        raise ReplicaError("Measurements must be an array")
    for measurement in measurements:
        if not isinstance(measurement, dict) or not isinstance(measurement.get("label"), str) or measurement.get("role") not in ("scale", "check") or not finite(measurement.get("valueMm")) or measurement["valueMm"] <= 0 or not finite(measurement.get("uncertaintyMm")) or measurement["uncertaintyMm"] < 0:
            raise ReplicaError("Invalid physical measurement")
    return manifest, photos, measurements, checksums, project_id


def import_capture(archive, store=DEFAULT_STORE):
    """Validate in quarantine, then atomically publish untouched original evidence."""
    archive, store = Path(archive), Path(store)
    if not archive.is_file() or archive.stat().st_size > MAX_ARCHIVE:
        raise ReplicaError("ZIP is absent or exceeds the 2 GiB import limit")
    store.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".import-", dir=store) as temporary:
        quarantine = Path(temporary)
        extracted = quarantine / "original"
        extracted.mkdir()
        try:
            with zipfile.ZipFile(archive) as source:
                entries = source.infolist()
                if len(entries) > MAX_FILES:
                    raise ReplicaError("ZIP contains too many entries")
                total, names = 0, set()
                for entry in entries:
                    relative = safe_relative(entry.filename.rstrip("/"))
                    if relative.casefold() in names:
                        raise ReplicaError("ZIP has duplicate or case-colliding paths")
                    names.add(relative.casefold())
                    mode = entry.external_attr >> 16
                    if stat.S_ISLNK(mode) or entry.flag_bits & 1:
                        raise ReplicaError("ZIP links and encrypted files are unsupported")
                    if entry.is_dir():
                        inside(extracted, relative).mkdir(parents=True, exist_ok=True)
                        continue
                    total += entry.file_size
                    if entry.file_size > MAX_ENTRY or total > MAX_EXPANDED or (entry.file_size > 1024**2 and entry.file_size / max(1, entry.compress_size) > 250):
                        raise ReplicaError("ZIP exceeds expansion limits")
                    target = inside(extracted, relative)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as incoming, target.open("xb") as outgoing:
                        shutil.copyfileobj(incoming, outgoing, length=1024**2)
        except (zipfile.BadZipFile, RuntimeError) as exc:
            raise ReplicaError("Invalid ZIP archive") from exc
        manifest, photos, measurements, checksums, project_id = validate_capture(extracted)
        destination = store / project_id
        archive_hash = sha256(archive)
        if destination.exists():
            if (destination / "project.json").is_file():
                previous = read_json(destination / "project.json")
                # ZIP compression and member timestamps may change on a phone re-export.
                # Equality of every verified payload hash is the stable evidence identity.
                same_payload = {k: v.lower() for k, v in previous.get("sourceHashes", {}).items()} == {k: v.lower() for k, v in checksums.items()}
                if previous.get("archiveSha256") == archive_hash or same_payload:
                    validate_capture(destination / "original")
                    return get_project(store, project_id)
            raise ReplicaError("Capture ID already exists with different original bytes; originals were preserved")
        shutil.copyfile(archive, quarantine / "original.zip")
        write_json(quarantine / "project.json", {"id": project_id, "importedAt": now(), "archiveSha256": archive_hash, "sourceHashes": checksums})
        # Same-filesystem rename publishes a complete project, never half-extracted evidence.
        os.rename(quarantine, destination)
    return get_project(store, project_id)


def asset_url(project_id, relative):
    return f"/api/projects/{project_id}/files/{quote(relative, safe='/')}"


def annotations(project):
    return read_json(project / "annotations.json") if (project / "annotations.json").is_file() else []


def measured_scale(records, run_id):
    candidates = [r for r in records if r["runId"] == run_id and r["role"] == "scale"]
    if not candidates:
        return {"status": "unscaled", "metersPerUnit": None}
    latest = candidates[-1]
    return {"status": "measured", "metersPerUnit": latest["valueMm"] / 1000 / latest["meshDistance"], "measurementId": latest["id"]}


def with_scale(run, records):
    run = dict(run)
    scale = measured_scale(records, run["id"])
    run["scale"] = scale
    run["scaleFactor"] = scale["metersPerUnit"]
    if run.get("model"):
        run["meshUrl"] = run["model"]["meshUrl"]
        run["textureUrl"] = run["model"]["textureUrl"]
    run["registeredImages"] = run.get("registeredCount", 0)
    return run


def get_project(store, project_id):
    project = project_path(store, project_id)
    manifest = read_json(project / "original" / "manifest.json")
    photos = read_json(project / "original" / "photos" / "photos.json")
    records = annotations(project)
    runs = [with_scale(read_json(p), records) for p in (project / "runs").glob("*/result.json")] if (project / "runs").is_dir() else []
    runs.sort(key=lambda r: r["createdAt"])
    latest = runs[-1] if runs else None
    cameras = {c["photoId"]: c for c in latest.get("cameras", [])} if latest else {}
    projected_records = []
    for record in records:
        item = dict(record)
        scale = measured_scale(records, record["runId"])
        item["status"] = scale["status"]
        item["scaleFactor"] = scale["metersPerUnit"]
        if scale["metersPerUnit"] is not None:
            item["predictedMm"] = item["meshDistance"] * scale["metersPerUnit"] * 1000
            item["residualMm"] = item["predictedMm"] - item["valueMm"]
        projected_records.append(item)
    epochs = sorted({p["epoch"] for p in photos})
    warnings = ["Photogrammetry begins with unknown metric scale.", "Photo colour is not calibrated object colour.", "Unseen surfaces and filled mesh regions are not measured evidence."]
    if len(epochs) > 1:
        warnings.append("Multiple coordinate epochs: select one for reconstruction; object reorientation requires separate alignment.")
    if manifest["status"] != "complete":
        warnings.append("Capture was interrupted; completeness has not been established.")
    return {"id": project_id, "name": manifest["name"], "manifest": manifest,
            "photos": [{**p, "url": asset_url(project_id, "original/" + p["file"]), "registeredCamera": cameras.get(p["id"])} for p in photos],
            "physicalMeasurements": read_json(project / "original" / "measurements.json"), "measurements": projected_records,
            "runs": runs, "latestRun": latest, "scale": latest["scale"] if latest else {"status": "unscaled", "metersPerUnit": None},
            "epochs": epochs, "warnings": warnings}


def list_projects(store=DEFAULT_STORE):
    store = Path(store)
    result = []
    if not store.is_dir():
        return result
    for p in store.iterdir():
        if not (p / "project.json").is_file():
            continue
        project = get_project(store, p.name)
        photos = project["photos"]
        result.append({"id": project["id"], "name": project["name"], "createdAt": project["manifest"]["createdAt"], "photoCount": len(photos),
                       "reconstructCount": sum(p["role"] == "reconstruct" for p in photos), "holdoutCount": sum(p["role"] == "holdout" for p in photos), "latestRun": project["latestRun"]})
    return sorted(result, key=lambda p: p["createdAt"], reverse=True)


def engine_path(value=None):
    candidate = value or os.environ.get("COLMAP_PATH")
    if candidate:
        candidate = Path(candidate).expanduser().resolve()
        if candidate.is_dir():
            for relative in ("bin/colmap.exe", "colmap.exe", "COLMAP.bat", "colmap"):
                if (candidate / relative).is_file():
                    candidate = candidate / relative
                    break
        if candidate.name.lower() == "colmap.bat" and (candidate.parent / "bin" / "colmap.exe").is_file():
            candidate = candidate.parent / "bin" / "colmap.exe"
        if candidate.is_file():
            return candidate
        raise ReplicaError("Configured COLMAP_PATH does not identify an existing engine")
    found = shutil.which("colmap") or shutil.which("COLMAP.bat")
    if found:
        return engine_path(found)
    raise ReplicaError("COLMAP is unavailable. Set COLMAP_PATH to the official portable COLMAP 4.2.1 CUDA folder or COLMAP.bat.")


def engine_env(engine):
    environment = os.environ.copy()
    root = engine.parent.parent if engine.parent.name.lower() == "bin" else engine.parent
    environment["PATH"] = str(engine.parent) + os.pathsep + environment.get("PATH", "")
    environment["QT_PLUGIN_PATH"] = str(root / "plugins")
    environment["QT_QPA_PLATFORM"] = "offscreen"
    return environment


def doctor(engine=None, store=DEFAULT_STORE):
    output = {"colmap": {"available": False, "path": None, "version": None, "commands": [], "errors": []},
              "python": sys.version.split()[0], "store": str(Path(store).resolve()),
              "privacy": "Local files only. No automatic capture or LLM uploads."}
    try:
        binary = engine_path(engine)
        probe = subprocess.run([str(binary), "-h"], env=engine_env(binary), capture_output=True, text=True, errors="replace", timeout=30)
        text = probe.stdout + probe.stderr
        available = [command for command in ENGINE_COMMANDS if re.search(r"\b" + command + r"\b", text)]
        missing = sorted(set(ENGINE_COMMANDS) - set(available))
        version = next((line.strip() for line in text.splitlines() if "COLMAP " in line), "Unknown version")
        output["colmap"].update({"path": str(binary), "version": version, "commands": available, "available": probe.returncode == 0 and not missing})
        if missing:
            output["colmap"]["errors"].append("Missing commands: " + ", ".join(missing))
        if "with CUDA" not in text and "with HIP" not in text:
            output["colmap"]["errors"].append("GPU dense reconstruction capability is not confirmed by the build.")
        if not re.search(r"COLMAP 4\.2\.1\b", text):
            output["colmap"]["errors"].append("This adapter is verified against COLMAP 4.2.1; configure that pinned version.")
        if probe.returncode:
            output["colmap"]["errors"].append(f"Engine probe exited {probe.returncode}")
        output["colmap"]["available"] = output["colmap"]["available"] and not output["colmap"]["errors"]
    except (ReplicaError, OSError, subprocess.SubprocessError) as exc:
        output["colmap"]["errors"].append(str(exc))
    return output


def parse_colmap_cameras(model, mapping):
    """COLMAP world→camera (OpenCV) into physical camera→world (OpenGL)."""
    calibrations = {}
    for line in (Path(model) / "cameras.txt").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split()
        camera_id, camera_model, width, height = int(fields[0]), fields[1], int(fields[2]), int(fields[3])
        params = [float(v) for v in fields[4:]]
        if camera_model in ("SIMPLE_PINHOLE", "SIMPLE_RADIAL", "RADIAL"):
            fx, cx, cy = params[:3]
            fy = fx
        elif camera_model in ("PINHOLE", "OPENCV", "FULL_OPENCV", "OPENCV_FISHEYE"):
            fx, fy, cx, cy = params[:4]
        else:
            raise ReplicaError(f"Unsupported recovered camera model: {camera_model}")
        calibrations[camera_id] = {"width": width, "height": height, "model": camera_model, "params": params, "intrinsics": {"fx": fx, "fy": fy, "cx": cx, "cy": cy}}
    cameras = []
    lines = iter((Path(model) / "images.txt").read_text().splitlines())
    for line in lines:
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split(maxsplit=9)
        if len(fields) != 10:
            raise ReplicaError("Invalid recovered COLMAP image record")
        image_id = int(fields[0])
        qw, qx, qy, qz, tx, ty, tz = [float(v) for v in fields[1:8]]
        camera_id, filename = int(fields[8]), fields[9]
        rotation = np.array([[1 - 2 * (qy*qy + qz*qz), 2 * (qx*qy - qw*qz), 2 * (qx*qz + qw*qy)],
                             [2 * (qx*qy + qw*qz), 1 - 2 * (qx*qx + qz*qz), 2 * (qy*qz - qw*qx)],
                             [2 * (qx*qz - qw*qy), 2 * (qy*qz + qw*qx), 1 - 2 * (qx*qx + qy*qy)]])
        camera_to_world = np.eye(4)
        camera_to_world[:3, :3] = rotation.T @ np.diag([1, -1, -1])
        camera_to_world[:3, 3] = -rotation.T @ np.array([tx, ty, tz])
        calibration = calibrations[camera_id]
        cameras.append({"photoId": mapping.get(filename, filename), "file": filename, "imageId": image_id,
                        "width": calibration["width"], "height": calibration["height"], "intrinsics": calibration["intrinsics"],
                        "cameraModel": calibration["model"], "cameraParams": calibration["params"],
                        "cameraToWorld": camera_to_world.flatten(order="F").tolist()})
        next(lines, "")  # The second record contains observed 2D points, including an empty line.
    return cameras


def check_ply(path, require_faces=True):
    with Path(path).open("rb") as file:
        header = bytearray()
        while len(header) < 65536:
            line = file.readline()
            header.extend(line)
            if line.strip() == b"end_header":
                break
            if not line:
                raise ReplicaError("PLY has no valid header")
    text = header.decode("ascii", errors="replace")
    vertices = re.search(r"element vertex (\d+)", text)
    faces = re.search(r"element face (\d+)", text)
    if not vertices or int(vertices[1]) == 0 or (require_faces and (not faces or int(faces[1]) == 0)):
        raise ReplicaError("Reconstruction produced no non-empty surface mesh")
    return {"vertices": int(vertices[1]), "faces": int(faces[1]) if faces else 0}


def execute_engine(directory, run, binary, stage, args):
    run.update(stage=stage, updatedAt=now())
    record = {"stage": stage, "args": [str(x) for x in args], "startedAt": now(), "log": f"logs/{stage}.log"}
    run["commands"].append(record)
    write_json(directory / "result.json", run)
    log = directory / record["log"]
    log.parent.mkdir(exist_ok=True)
    with log.open("wb") as output:
        completed = subprocess.run([str(binary)] + [str(x) for x in args], env=engine_env(binary), cwd=directory,
                                   stdout=output, stderr=subprocess.STDOUT, timeout=24*3600)
    record.update(exitCode=completed.returncode, finishedAt=now())
    write_json(directory / "result.json", run)
    if completed.returncode != 0:
        tail = log.read_text(encoding="utf-8", errors="replace")[-3500:]
        raise ReplicaError(f"COLMAP {stage} failed (exit {completed.returncode}). See {record['log']}.\n{tail}")


def finish_surface(project, directory, run, binary, trim=5):
    dense = directory / "dense"
    fused, mesh, textured = dense / "fused.ply", dense / "mesh-poisson.ply", dense / "textured"
    run["pointCloud"] = check_ply(fused, require_faces=False)
    execute_engine(directory, run, binary, "mesh", ["poisson_mesher", "--input_path", fused, "--output_path", mesh,
                   "--PoissonMeshing.trim", trim, "--log_target", "stderr", "--log_color", "0"])
    run["geometry"] = check_ply(mesh)
    execute_engine(directory, run, binary, "texture", ["mesh_texturer", "--workspace_path", dense, "--input_path", mesh,
                   "--output_path", textured, "--MeshTextureMapping.apply_color_correction", "0", "--MeshTextureMapping.inpaint_radius", "0",
                   "--log_target", "stderr", "--log_color", "0"])
    run["geometry"] = check_ply(textured / "mesh.ply")
    with Image.open(textured / "texture.png") as atlas:
        atlas.verify()
    mesh_relative = (textured / "mesh.ply").relative_to(project).as_posix()
    texture_relative = (textured / "texture.png").relative_to(project).as_posix()
    run["model"] = {"meshPath": mesh_relative, "texturePath": texture_relative,
                    "meshUrl": asset_url(project.name, mesh_relative), "textureUrl": asset_url(project.name, texture_relative)}
    run["processing"] = {"meshing": "Poisson inferred surface", "poissonDensityTrim": trim, "textureColorCorrection": False,
                         "textureInpaintRadius": 0, "maxImageSize": run["maxImageSize"], "holdoutUsed": False, "arcorePosesUsedForGeometry": False}
    run["warnings"].append(f"Poisson density trim is {trim}; this numerical sampling threshold does not establish a physical detail resolution.")
    finish_validation(project, directory, run, binary)
    run.update(status="succeeded", stage="complete", updatedAt=now())


def finish_validation(project, directory, run, binary):
    run.update(stage="holdout-localization", updatedAt=now())
    write_json(directory / "result.json", run)
    try:
        localize_holdouts(project, run, binary)
    except ArtifactMutationError as exc:
        run["validation"] = {**run.get("validation", {}), "status": "failed", "productionHashesUnchanged": False,
                             "error": str(exc), "finishedAt": now()}
        run.update(status="failed", stage="failed", failedStage="production-artifact-audit", error=str(exc), updatedAt=now())
        write_json(directory / "result.json", run)
        raise
    except Exception as exc:
        run["validation"] = {**run.get("validation", {}), "status": "failed", "error": str(exc), "finishedAt": now()}
        run["warnings"].append("Holdout localization could not complete. The reconstructed surface is available, but independent photo comparison is not established: " + str(exc))
    write_json(directory / "result.json", run)


def photo_group(photo):
    key = json.dumps([photo["cameraId"], photo["width"], photo["height"], photo["calibrationStatus"], photo["intrinsics"]], sort_keys=True)
    return hashlib.sha256(key.encode()).hexdigest()[:12]


def sparse_points(model):
    points = {}
    for line in (Path(model) / "points3D.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            fields = line.split()
            points[int(fields[0])] = [float(value) for value in fields[1:4]]
    return points


def verify_fixed_geometry(before_points, after_points, before_cameras, after_cameras):
    """Validation cameras may be fitted; existing world geometry and poses cannot move."""
    if set(before_points) != set(after_points) or any(not np.allclose(before_points[key], after_points[key], atol=1e-12, rtol=1e-12) for key in before_points):
        raise ReplicaError("Validation changed sparse point geometry; holdout cameras were not accepted")
    recovered = {camera["photoId"]: camera for camera in after_cameras}
    for camera in before_cameras:
        candidate = recovered.get(camera["photoId"])
        if not candidate or not np.allclose(camera["cameraToWorld"], candidate["cameraToWorld"], atol=1e-10, rtol=1e-10):
            raise ReplicaError("Validation moved a reconstruction camera; holdout cameras were not accepted")


def protected_hashes(project, relative_paths):
    hashes = {}
    for relative in relative_paths:
        try:
            hashes[relative] = sha256(project / relative)
        except OSError as exc:
            raise ArtifactMutationError(f"Protected production artifact is missing or unreadable: {relative}") from exc
    return hashes


def localize_holdouts(project, run, binary):
    """Separate, geometry-frozen validation. Never put holdouts into dense reconstruction."""
    directory = project / "runs" / run["id"]
    all_photos = read_json(project / "original" / "photos" / "photos.json")
    heldouts = [p for p in all_photos if p["role"] == "holdout" and p["epoch"] == run["epoch"]]
    if not heldouts:
        run["validation"] = {"status": "not-applicable", "registeredCount": 0, "heldoutCount": 0,
                             "warning": "No holdout photographs exist in this coordinate epoch."}
        return
    validation_id = "v-" + uuid4().hex[:8]
    validation = directory / validation_id
    validation.mkdir(parents=True)
    report = {"id": validation_id, "status": "running", "createdAt": now(), "heldoutCount": len(heldouts), "registeredCount": 0,
              "geometryFixed": True, "bundleAdjustmentRun": False, "denseReconstructionRun": False, "textureGenerationRun": False, "commands": []}
    run["validation"] = report
    protected = [directory / "database.db", project / run["model"]["meshPath"],
                 project / run["model"]["texturePath"]]
    # Include original sparse camera/point binaries and dense calibration, not just the final surface.
    protected.extend(p for p in (directory / "sparse").rglob("*") if p.is_file())
    protected.extend(p for p in (directory / "dense" / "sparse").rglob("*") if p.is_file())
    before_hashes = protected_hashes(project, [p.relative_to(project).as_posix() for p in protected])
    report["productionHashesBefore"] = before_hashes
    write_json(validation / "validation.json", report)
    write_json(directory / "result.json", run)
    try:
        images = validation / "images"
        shutil.copytree(directory / "images", images)
        database = validation / "database.db"
        with sqlite3.connect((directory / "database.db").resolve().as_uri() + "?mode=ro", uri=True) as source, sqlite3.connect(database) as target:
            source.backup(target)
        mapping = read_json(directory / "photo-map.json")
        holdout_names = []
        for photo in heldouts:
            relative = f"{photo_group(photo)}/{photo['id']}.jpg"
            target = images / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(inside(project / "original", photo["file"]), target)
            mapping[relative] = photo["id"]
            holdout_names.append(relative)
        image_list = validation / "holdouts.txt"
        image_list.write_text("\n".join(holdout_names) + "\n", encoding="utf-8")
        write_json(validation / "photo-map.json", mapping)

        def execute(stage, args):
            record = {"stage": stage, "args": [str(arg) for arg in args], "startedAt": now(), "log": f"logs/{stage}.log"}
            report["commands"].append(record)
            write_json(validation / "validation.json", report)
            log = validation / record["log"]
            log.parent.mkdir(exist_ok=True)
            with log.open("wb") as output:
                result = subprocess.run([str(binary)] + [str(arg) for arg in args], env=engine_env(binary), cwd=validation,
                                        stdout=output, stderr=subprocess.STDOUT, timeout=3600)
            record.update(exitCode=result.returncode, finishedAt=now())
            if result.returncode:
                raise ReplicaError(f"Holdout localization {stage} failed ({result.returncode}): {log.read_text(errors='replace')[-1600:]}")

        execute("features", ["feature_extractor", "--database_path", database, "--image_path", images,
                             "--image_list_path", image_list, "--ImageReader.single_camera_per_folder", "1",
                             "--FeatureExtraction.max_image_size", run["maxImageSize"], "--FeatureExtraction.use_gpu", "0", "--log_target", "stderr", "--log_color", "0"])
        execute("matching", ["exhaustive_matcher", "--database_path", database, "--FeatureMatching.use_gpu", "0", "--log_target", "stderr", "--log_color", "0"])
        sources = sorted(p for p in (directory / "sparse").iterdir() if p.is_dir() and (p / "images.bin").is_file())
        if len(sources) != 1:
            raise ReplicaError("Validation needs exactly one frozen source reconstruction")
        model = validation / "model"
        model.mkdir()
        execute("localize", ["image_registrator", "--database_path", database, "--input_path", sources[0], "--output_path", model,
                             "--Mapper.image_list_path", image_list, "--log_target", "stderr", "--log_color", "0"])
        model_text = validation / "model-text"
        model_text.mkdir()
        execute("cameras", ["model_converter", "--input_path", model, "--output_path", model_text, "--output_type", "TXT"])
        raw_cameras = parse_colmap_cameras(model_text, mapping)
        baseline_cameras = parse_colmap_cameras(directory / "sparse-text", mapping)
        verify_fixed_geometry(sparse_points(directory / "sparse-text"), sparse_points(model_text), baseline_cameras, raw_cameras)
        derivative = validation / "u"
        execute("undistort", ["image_undistorter", "--image_path", images, "--input_path", model, "--output_path", derivative,
                              "--output_type", "COLMAP", "--max_image_size", run["maxImageSize"],
                              "--log_target", "stderr", "--log_color", "0"])
        derivative_text = validation / "undistorted-cameras"
        derivative_text.mkdir()
        execute("review-cameras", ["model_converter", "--input_path", derivative / "sparse", "--output_path", derivative_text, "--output_type", "TXT"])
        candidates = parse_colmap_cameras(derivative_text, mapping)
        heldout_ids = {p["id"] for p in heldouts}
        localized = []
        for camera in candidates:
            if camera["photoId"] not in heldout_ids:
                continue
            camera.update(role="holdout", imageKind="undistorted", validationId=validation_id,
                          photoUrl=asset_url(project.name, (derivative / "images" / camera["file"]).relative_to(project).as_posix()),
                          originalFile=next(p["file"] for p in heldouts if p["id"] == camera["photoId"]))
            if not (derivative / "images" / camera["file"]).is_file():
                raise ReplicaError("Localized holdout has no corresponding undistorted photo")
            localized.append(camera)
        report.update(status="succeeded" if localized else "unlocalized", registeredCount=len(localized),
                      registeredPhotoIds=[c["photoId"] for c in localized],
                      unregisteredPhotoIds=sorted(heldout_ids - {c["photoId"] for c in localized}),
                      warning="Pose fitting against frozen geometry enables independent appearance checks; it is not a physical accuracy certificate.")
        run["cameras"] = [c for c in run.get("cameras", []) if c.get("role") != "holdout"] + localized
        run["warnings"] = [w for w in run["warnings"] if "their poses have not been independently localized" not in w]
        run["warnings"].append(f"{len(localized)} of {len(heldouts)} held-out views localized against frozen geometry. Review these independent appearance comparisons; camera fitting is not a physical accuracy certificate.")
        if report["unregisteredPhotoIds"]:
            run["warnings"].append(f"{len(report['unregisteredPhotoIds'])} held-out views could not be localized; no comparison pose is assumed for them.")
    except Exception as exc:
        report.update(status="failed", error=str(exc))
        run["warnings"].append("Holdout localization failed: " + str(exc))
    finally:
        mutation = None
        try:
            after_hashes = protected_hashes(project, before_hashes)
            report["productionHashesAfter"] = after_hashes
            report["productionHashesUnchanged"] = before_hashes == after_hashes
            if not report["productionHashesUnchanged"]:
                mutation = ArtifactMutationError("Validation modified a protected production artifact")
        except ArtifactMutationError as exc:
            mutation = exc
        if mutation:
            report.update(status="failed", productionHashesUnchanged=False, error=str(mutation))
        report["finishedAt"] = now()
        run["updatedAt"] = now()
        try:
            write_json(validation / "validation.json", report)
            write_json(directory / "result.json", run)
        finally:
            if mutation:
                raise mutation


class ReplicaStore:
    """Owns per-project jobs and derivative annotations; originals are never written."""
    def __init__(self, root=DEFAULT_STORE, engine=None):
        self.root, self.engine = Path(root), engine
        self.lock = threading.RLock()
        self.jobs = {}
        self.recover_abandoned_runs()

    def recover_abandoned_runs(self):
        if not self.root.is_dir():
            return
        try:
            engine_lock = EngineLock(self.root)
        except ReplicaError:
            return  # Another CLI/server still owns its live job.
        try:
            for path in self.root.glob("*/runs/*/result.json"):
                run = read_json(path)
                # Legacy jobs without an ownership record are not declared dead.
                if run.get("ownerPid") and run.get("status") in ("queued", "running"):
                    run.update(status="failed", stage="interrupted", error="The prior process ended before this run completed. Originals are intact. Start a new reconstruction.", updatedAt=now())
                    write_json(path, run)
        finally:
            engine_lock.close()

    def prepare_run(self, project_id, options=None):
        options = options or {}
        if not isinstance(options, dict) or set(options) - {"epoch", "maxImageSize"}:
            raise ReplicaError("Unknown reconstruction options")
        max_image_size = options.get("maxImageSize", 3200)
        if isinstance(max_image_size, bool) or not isinstance(max_image_size, int) or not 256 <= max_image_size <= 12000:
            raise ReplicaError("maxImageSize must be an integer from 256 to 12000")
        with self.lock:
            if project_id in self.jobs and self.jobs[project_id].is_alive():
                raise ReplicaError("This project already has a running reconstruction")
            project = get_project(self.root, project_id)
            reconstruct_photos = [p for p in project["photos"] if p["role"] == "reconstruct"]
            epochs = sorted({p["epoch"] for p in reconstruct_photos})
            epoch = options.get("epoch")
            if epoch is None:
                if len(epochs) != 1:
                    raise ReplicaError("Select exactly one coordinate epoch for reconstruction")
                epoch = epochs[0]
            selected = [p for p in reconstruct_photos if p["epoch"] == epoch]
            if len(selected) < 3:
                raise ReplicaError("At least three reconstruction photos in the selected epoch are required")
            run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid4().hex[:8]
            directory = project_path(self.root, project_id) / "runs" / run_id
            directory.mkdir(parents=True)
            run = {"id": run_id, "status": "queued", "stage": "queued", "createdAt": now(), "updatedAt": now(),
                   "ownerPid": os.getpid(),
                   "epoch": epoch, "photoCount": len(selected), "holdoutCount": sum(p["role"] == "holdout" for p in project["photos"]),
                   "registeredCount": 0, "unregisteredPhotoIds": [], "commands": [], "cameras": [], "scale": {"status": "unscaled", "metersPerUnit": None},
                   "selectedPhotoIds": [p["id"] for p in selected], "heldoutPhotoIds": [p["id"] for p in project["photos"] if p["role"] == "holdout"],
                   "maxImageSize": max_image_size, "warnings": [
                       "Metric scale is unknown until a measured distance is linked to two mesh points.",
                       "Photogrammetry estimates/refines calibration and camera poses; ARCore evidence is retained but is not treated as exact JPEG calibration.",
                       "Holdout images are excluded from reconstruction and texture generation; their poses have not been independently localized.",
                       "Poisson meshing can smooth details and infer surfaces across gaps. Such surfaces are not measured.",
                       "Texture exposure/colour comes from photographs and is not a calibrated material measurement.",
                       "The full stationary scene is reconstructed; background removal and object cropping require review."]}
            if any(p["width"] < 1600 or p["height"] < 1200 for p in selected):
                run["warnings"].append("Some images are low resolution; fine object detail cannot be assumed.")
            if any(p.get("quality", {}).get("sharpness") is None for p in selected):
                run["warnings"].append("Sharpness evidence is missing on one or more source photographs.")
            write_json(directory / "result.json", run)
            return run

    def start(self, project_id, options=None, background=True):
        with self.lock:
            engine_lock = EngineLock(self.root)
            try:
                run = self.prepare_run(project_id, options)
            except Exception:
                engine_lock.close()
                raise
            if background:
                worker = threading.Thread(target=self.process_locked, args=(project_id, run["id"], engine_lock), daemon=False, name="reconstruction-" + project_id)
                self.jobs[project_id] = worker
                worker.start()
                return with_scale(run, annotations(project_path(self.root, project_id)))
        self.process_locked(project_id, run["id"], engine_lock)
        return self.run_status(project_id, run["id"])

    def process_locked(self, project_id, run_id, engine_lock):
        try:
            self.process(project_id, run_id)
        finally:
            engine_lock.close()

    def run_status(self, project_id, run_id=None):
        project = get_project(self.root, project_id)
        if run_id:
            return next(r for r in project["runs"] if r["id"] == run_id)
        return project["latestRun"] or {"status": "idle"}

    def process(self, project_id, run_id):
        project = project_path(self.root, project_id)
        directory = project / "runs" / run_id
        run = read_json(directory / "result.json")
        try:
            run.update(status="running", stage="verify-originals", updatedAt=now())
            write_json(directory / "result.json", run)
            manifest, all_photos, _, _, _ = validate_capture(project / "original")
            photos = [p for p in all_photos if p["role"] == "reconstruct" and p["epoch"] == run["epoch"]]
            probe = doctor(self.engine, self.root)
            run["engine"] = probe["colmap"]
            if not probe["colmap"]["available"] or probe["colmap"]["errors"]:
                raise ReplicaError("; ".join(probe["colmap"]["errors"]) or "COLMAP capability probe failed")
            binary = engine_path(self.engine)
            images = directory / "images"
            images.mkdir()
            mapping = {}
            for p in photos:
                # Identical camera/size/calibration groups share estimated intrinsics.
                group = photo_group(p)
                relative = f"{group}/{p['id']}.jpg"
                target = images / relative
                target.parent.mkdir(exist_ok=True)
                shutil.copyfile(inside(project / "original", p["file"]), target)
                mapping[relative] = p["id"]
            write_json(directory / "photo-map.json", mapping)
            database, sparse, dense = directory / "database.db", directory / "sparse", directory / "dense"
            sparse.mkdir()
            dense.mkdir()

            def command(stage, args):
                execute_engine(directory, run, binary, stage, args)

            command("features", ["feature_extractor", "--database_path", database, "--image_path", images,
                                  "--ImageReader.single_camera_per_folder", "1", "--FeatureExtraction.max_image_size", run["maxImageSize"],
                                  "--log_target", "stderr", "--log_color", "0"])
            command("matching", ["exhaustive_matcher", "--database_path", database, "--log_target", "stderr", "--log_color", "0"])
            command("mapping", ["mapper", "--database_path", database, "--image_path", images, "--output_path", sparse,
                                 "--log_target", "stderr", "--log_color", "0"])
            models = sorted(p for p in sparse.iterdir() if p.is_dir() and ((p / "images.bin").is_file() or (p / "images.txt").is_file()))
            if len(models) != 1:
                raise ReplicaError(f"Expected one connected reconstruction, found {len(models)}. Add overlapping views; disconnected components are not silently merged.")
            model = models[0]
            sparse_text = directory / "sparse-text"
            sparse_text.mkdir()
            command("sparse-cameras", ["model_converter", "--input_path", model, "--output_path", sparse_text, "--output_type", "TXT"])
            original_cameras = parse_colmap_cameras(sparse_text, mapping)
            registered = {c["photoId"] for c in original_cameras}
            run["registeredCount"] = len(registered)
            run["unregisteredPhotoIds"] = [p["id"] for p in photos if p["id"] not in registered]
            if len(registered) < 3:
                raise ReplicaError("Fewer than three source images registered into the model")
            if run["unregisteredPhotoIds"]:
                run["warnings"].append(f"{len(run['unregisteredPhotoIds'])} reconstruction images did not register. Review coverage before accepting the result.")
            command("undistort", ["image_undistorter", "--image_path", images, "--input_path", model, "--output_path", dense,
                                   "--output_type", "COLMAP", "--max_image_size", run["maxImageSize"], "--log_target", "stderr", "--log_color", "0"])
            dense_text = directory / "dense-cameras"
            dense_text.mkdir()
            command("review-cameras", ["model_converter", "--input_path", dense / "sparse", "--output_path", dense_text, "--output_type", "TXT"])
            cameras = parse_colmap_cameras(dense_text, mapping)
            for camera in cameras:
                camera["originalFile"] = next(p["file"] for p in photos if p["id"] == camera["photoId"])
                camera["photoUrl"] = asset_url(project_id, (dense / "images" / camera["file"]).relative_to(project).as_posix())
                camera["imageKind"] = "undistorted"
            run["cameras"] = cameras
            run["originalCameras"] = original_cameras
            run["warnings"].append("Matched-camera comparisons use the undistorted derivative photo at camera.photoUrl; raw source photos retain their original lens distortion.")
            command("stereo", ["patch_match_stereo", "--workspace_path", dense, "--workspace_format", "COLMAP",
                               "--PatchMatchStereo.geom_consistency", "1", "--PatchMatchStereo.max_image_size", run["maxImageSize"],
                               "--PatchMatchStereo.cache_size", "4", "--log_target", "stderr", "--log_color", "0"])
            fused = dense / "fused.ply"
            command("fusion", ["stereo_fusion", "--workspace_path", dense, "--workspace_format", "COLMAP", "--input_type", "geometric", "--output_path", fused,
                               "--log_target", "stderr", "--log_color", "0"])
            finish_surface(project, directory, run, binary)
        except Exception as exc:
            run.update(status="failed", failedStage=run["stage"], stage="failed", error=str(exc), updatedAt=now())
        finally:
            write_json(directory / "result.json", run)

    def add_measurement(self, project_id, value):
        if not isinstance(value, dict) or set(value) - {"label", "valueMm", "role", "a", "b", "runId"}:
            raise ReplicaError("Invalid measurement request")
        if not isinstance(value.get("label"), str) or not 1 <= len(value["label"]) <= 200 or value.get("role") not in ("scale", "check") or not finite(value.get("valueMm")) or value["valueMm"] <= 0:
            raise ReplicaError("Measurement needs label, positive valueMm and scale/check role")
        for endpoint in (value.get("a"), value.get("b")):
            if not isinstance(endpoint, list) or len(endpoint) != 3 or not all(finite(v) for v in endpoint):
                raise ReplicaError("Select two finite mesh points")
        distance = float(np.linalg.norm(np.array(value["a"]) - np.array(value["b"])))
        if distance <= 1e-10:
            raise ReplicaError("Measurement endpoints must be distinct")
        with self.lock:
            project = get_project(self.root, project_id)
            run_id = value.get("runId") or (project["latestRun"] or {}).get("id")
            run = next((r for r in project["runs"] if r["id"] == run_id), None)
            if not run or run["status"] != "succeeded":
                raise ReplicaError("A succeeded reconstruction is required for mesh measurements")
            path = project_path(self.root, project_id)
            records = annotations(path)
            records.append({**value, "id": str(uuid4()), "runId": run_id, "meshDistance": distance, "createdAt": now()})
            write_json(path / "annotations.json", records)
        return get_project(self.root, project_id)

    def validate(self, project_id):
        with self.lock:
            engine_lock = EngineLock(self.root)
        try:
            project = project_path(self.root, project_id)
            view = get_project(self.root, project_id)
            run = view["latestRun"]
            if not run or run["status"] != "succeeded":
                raise ReplicaError("A succeeded reconstruction is required before holdout localization")
            validate_capture(project / "original")
            binary = engine_path(self.engine)
            # Work on the stored run, not API aliases derived for presentation.
            run = read_json(project / "runs" / run["id"] / "result.json")
            finish_validation(project, project / "runs" / run["id"], run, binary)
            run.update(stage="complete", updatedAt=now())
            write_json(project / "runs" / run["id"] / "result.json", run)
            return with_scale(run, annotations(project))
        finally:
            engine_lock.close()

    def resume(self, project_id, trim=5):
        """New derivative run from a preserved fused cloud; expensive stereo is not repeated."""
        if not finite(trim) or not 0 <= trim <= 20:
            raise ReplicaError("Poisson density trim must be from 0 to 20")
        engine_lock = EngineLock(self.root)
        run = None
        try:
            project = project_path(self.root, project_id)
            previous = get_project(self.root, project_id)["latestRun"]
            if not previous:
                raise ReplicaError("No prior reconstruction exists to resume")
            source = project / "runs" / previous["id"]
            check_ply(source / "dense" / "fused.ply", require_faces=False)
            validate_capture(project / "original")
            binary = engine_path(self.engine)
            run = self.prepare_run(project_id, {"epoch": previous["epoch"], "maxImageSize": previous["maxImageSize"]})
            directory = project / "runs" / run["id"]
            run.update(status="running", stage="resume-copy", engine=doctor(self.engine, self.root)["colmap"],
                       registeredCount=previous["registeredCount"], unregisteredPhotoIds=previous.get("unregisteredPhotoIds", []),
                       cameras=previous.get("cameras", []), originalCameras=previous.get("originalCameras", []))
            run["resumedFrom"] = {"runId": previous["id"], "fusedSha256": sha256(source / "dense" / "fused.ply"),
                                  "databaseSha256": sha256(source / "database.db"), "sourceError": previous.get("error"),
                                  "skippedStages": ["features", "matching", "mapping", "undistort", "stereo", "fusion"]}
            for relative in ("images", "sparse", "sparse-text", "dense-cameras", "dense/images", "dense/sparse"):
                shutil.copytree(source / relative, directory / relative)
            for relative in ("database.db", "photo-map.json", "dense/fused.ply"):
                target = directory / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / relative, target)
            # A new run owns its image URLs and never changes the prior run's files.
            run["cameras"] = [dict(camera) for camera in run["cameras"] if camera.get("role") != "holdout"]
            for camera in run["cameras"]:
                if camera.get("photoUrl"):
                    camera["photoUrl"] = camera["photoUrl"].replace(f"/runs/{previous['id']}/", f"/runs/{run['id']}/")
            write_json(directory / "result.json", run)
            reusable = previous.get("model") and previous.get("processing", {}).get("poissonDensityTrim") == trim
            if reusable:
                check_ply(project / previous["model"]["meshPath"])
                with Image.open(project / previous["model"]["texturePath"]) as atlas:
                    atlas.verify()
                shutil.copytree(source / "dense" / "textured", directory / "dense" / "textured")
                if (source / "dense" / "mesh-poisson.ply").is_file():
                    shutil.copyfile(source / "dense" / "mesh-poisson.ply", directory / "dense" / "mesh-poisson.ply")
                run["model"] = {key: value.replace(f"runs/{previous['id']}/", f"runs/{run['id']}/") for key, value in previous["model"].items()}
                run["geometry"] = dict(previous["geometry"])
                run["processing"] = dict(previous["processing"])
                run["resumedFrom"].update(sourceMeshSha256=sha256(project / previous["model"]["meshPath"]),
                                          sourceTextureSha256=sha256(project / previous["model"]["texturePath"]))
                run["resumedFrom"]["skippedStages"].extend(["mesh", "texture"])
                finish_validation(project, directory, run, binary)
                run.update(status="succeeded", stage="complete", updatedAt=now())
            else:
                finish_surface(project, directory, run, binary, trim)
        except Exception as exc:
            if run is None:
                raise
            run.update(status="failed", failedStage=run["stage"], stage="failed", error=str(exc), updatedAt=now())
        finally:
            if run is not None:
                write_json(project / "runs" / run["id"] / "result.json", run)
            engine_lock.close()
        return with_scale(run, annotations(project))


def evidence(store, project_id):
    """Create a new, local evidence packet. No network or model client exists here."""
    project = get_project(store, project_id)
    path = project_path(store, project_id)
    validate_capture(path / "original")
    packet_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid4().hex[:8]
    directory = path / "evidence" / packet_id
    directory.mkdir(parents=True)
    hashes = {}
    for file in sorted((path / "original").rglob("*")):
        if file.is_file():
            hashes[file.relative_to(path).as_posix()] = sha256(file)
    write_json(directory / "source-hashes.json", hashes)
    report = {"generatedAt": now(), "project": project, "sourceHashes": hashes,
              "privacy": "No files have been uploaded by this tool.", "proof": "Software and source evidence only. Real capture and physical fidelity are not established."}
    write_json(directory / "report.json", report)
    # Paginated source sheets avoid collapsing every detail into an unreadable thumbnail.
    sheet_urls = []
    photos = project["photos"]
    for start in range(0, len(photos), 24):
        selected = photos[start:start+24]
        sheet = Image.new("RGB", (4*320, math.ceil(len(selected)/4)*270), "#171b22")
        draw = ImageDraw.Draw(sheet)
        for index, photo in enumerate(selected):
            with Image.open(inside(path / "original", photo["file"])) as source:
                display = ImageOps.exif_transpose(source).convert("RGB")
                display.thumbnail((310, 230))
                x, y = (index % 4)*320, (index // 4)*270
                sheet.paste(display, (x+(320-display.width)//2, y))
                draw.text((x+8, y+235), f"{photo['id']} | {photo['role']} | {photo['width']}x{photo['height']}", fill="white")
                draw.text((x+8, y+252), photo["epoch"][:35], fill="#aac0d8")
        filename = f"contact-sheet-{start//24+1:03}.jpg"
        sheet.save(directory / filename, quality=90)
        sheet_urls.append(asset_url(project_id, (directory / filename).relative_to(path).as_posix()))
    latest = project["latestRun"]
    summary = f"# Evidence: {project['name']}\n\nCapture ID: `{project_id}`.\n\n"
    summary += f"{len(photos)} original photos; {sum(p['role']=='holdout' for p in photos)} holdouts.\n\n"
    summary += "Original JPEG bytes, calibration evidence and physical observations remain in `original/`. Contact sheets rotate only for display.\n\n"
    summary += "## Reconstruction\n\n" + (f"Run `{latest['id']}`: **{latest['status']}**, {latest.get('registeredCount',0)} registered cameras.\n\n" if latest else "No reconstruction has run.\n\n")
    summary += "## Limits\n\n" + "\n".join("- " + warning for warning in project["warnings"] + (latest.get("warnings", []) if latest else [])) + "\n\n"
    summary += "Metric scale is a unit conversion from selected endpoints, not proof of geometric accuracy. Inspect independent check residuals in report.json. No phone or printed object has been verified by this packet.\n"
    (directory / "report.md").write_text(summary, encoding="utf-8")
    prompt = (f"Inspect the local object capture in `{path.resolve()}`.\n\n"
              f"Evidence directory: `{directory.resolve()}`\n"
              f"Read `{(directory / 'report.json').resolve()}` and `{(directory / 'source-hashes.json').resolve()}` first.\n"
              f"Original full-resolution JPEGs and capture metadata: `{(path / 'original').resolve()}`.\n"
              f"Contact sheets: `{directory.resolve() / 'contact-sheet-001.jpg'}` (additional numbered sheets are in the same directory).\n\n"
              "Inspect this local capture and reconstruction using those reports, contact sheets and the original full-resolution photos. "
              "The goal is faithful replication of the observed LEGO car: shape, size, colour and fine geometry. "
              "Do not invent missing surfaces, replace it with a generic car, or treat photographic texture as geometric detail. "
              "Do not upload source images or use a cloud service without the user's explicit instruction. "
              "Report unobserved areas, registration failures, dimension-check errors, uncertain colours, blur, glare and texture-versus-geometry discrepancies. "
              "Use undistorted derivative photos and the matching recovered cameras for projections. Keep the mesh raw until an explicit measured endpoint pair establishes scale. "
              "Holdout photographs have not entered reconstruction or texture generation. Use a holdout comparison pose only when the validation report records successful localization against frozen geometry and its camera record exists. "
              "Propose specific extra photos or physical measurements required to reduce uncertainty. Never describe software-only checks as physical or perfect replication.\n")
    (directory / "agent-prompt.md").write_text(prompt, encoding="utf-8")
    return {"path": str(directory), "reportUrl": asset_url(project_id, (directory / "report.md").relative_to(path).as_posix()),
            "reportJsonUrl": asset_url(project_id, (directory / "report.json").relative_to(path).as_posix()),
            "promptUrl": asset_url(project_id, (directory / "agent-prompt.md").relative_to(path).as_posix()),
            "contactSheetUrl": sheet_urls[0], "contactSheetUrls": sheet_urls,
            "hashesUrl": asset_url(project_id, (directory / "source-hashes.json").relative_to(path).as_posix())}


class ReplicaHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store, static):
        self.replica, self.static = store, Path(static).resolve()
        super().__init__(address, ReplicaHandler)


class ReplicaHandler(BaseHTTPRequestHandler):
    server_version = "PixelReplica/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        status = str(args[1]) if len(args) > 1 and str(args[1]).isdigit() else "event"
        sys.stderr.write(f"local review HTTP {status}\n")

    def send_json(self, value, status=200):
        data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def local_request(self):
        expected = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
        if self.headers.get("Host") not in expected:
            raise ReplicaError("Non-local Host header rejected")
        origin = self.headers.get("Origin")
        if origin and origin not in {"http://" + host for host in expected}:
            raise ReplicaError("Cross-origin request rejected")

    def body_length(self, maximum):
        if self.headers.get("Transfer-Encoding"):
            raise ReplicaError("Chunked request bodies are unsupported")
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError as exc:
            raise ReplicaError("Invalid Content-Length") from exc
        if not 0 <= length <= maximum:
            raise ReplicaError("Request body is absent or exceeds size limit")
        return length

    def json_body(self):
        if self.headers.get_content_type() != "application/json":
            raise ReplicaError("Expected application/json")
        try:
            return json.loads(self.rfile.read(self.body_length(1024**2)), object_pairs_hook=unique_json_keys, parse_constant=lambda x: (_ for _ in ()).throw(ReplicaError("Invalid JSON number")))
        except json.JSONDecodeError as exc:
            raise ReplicaError("Invalid JSON request") from exc

    def send_file(self, path, asset=False):
        path = Path(path)
        if not path.is_file():
            raise FileNotFoundError("File not found")
        self.send_response(200)
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if asset:
            content_type = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".json": "application/json",
                            ".md": "text/plain; charset=utf-8", ".log": "text/plain; charset=utf-8", ".txt": "text/plain; charset=utf-8"}.get(path.suffix.lower(), "application/octet-stream")
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-cache")
        if asset:
            self.send_header("Content-Security-Policy", "sandbox; default-src 'none'")
        self.end_headers()
        with path.open("rb") as source:
            shutil.copyfileobj(source, self.wfile, length=1024**2)

    def do_GET(self):
        try:
            self.local_request()
            route = unquote(urlparse(self.path).path)
            parts = route.strip("/").split("/")
            store = self.server.replica
            if route == "/api/status":
                return self.send_json(doctor(store.engine, store.root))
            if route == "/api/projects":
                return self.send_json(list_projects(store.root))
            if len(parts) >= 3 and parts[:2] == ["api", "projects"]:
                project_id = parts[2]
                path = project_path(store.root, project_id)
                if len(parts) == 3:
                    return self.send_json(get_project(store.root, project_id))
                if len(parts) == 4 and parts[3] == "status":
                    return self.send_json(store.run_status(project_id))
                if len(parts) == 4 and parts[3] == "evidence":
                    return self.send_json(evidence(store.root, project_id))
                if len(parts) >= 5 and parts[3] == "files":
                    return self.send_file(inside(path, "/".join(parts[4:])), asset=True)
                raise FileNotFoundError("API route not found")
            if route.startswith("/api/"):
                raise FileNotFoundError("API route not found")
            relative = route.lstrip("/") or "index.html"
            target = inside(self.server.static, relative)
            if not target.exists() and "." not in PurePosixPath(relative).name:
                target = self.server.static / "index.html"
            if not target.exists() and relative == "index.html":
                return self.send_json({"error": "Review UI is not built. Run npm run object:build, then refresh."}, 503)
            return self.send_file(target)
        except FileNotFoundError as exc:
            self.send_json({"error": str(exc)}, 404)
        except ReplicaError as exc:
            self.send_json({"error": str(exc)}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            self.send_json({"error": str(exc)}, 500)

    def do_POST(self):
        temporary = None
        try:
            self.local_request()
            route = unquote(urlparse(self.path).path)
            store = self.server.replica
            parts = route.strip("/").split("/")
            if route == "/api/import":
                if self.headers.get_content_type() != "application/zip":
                    raise ReplicaError("Import expects a raw application/zip body")
                remaining = self.body_length(MAX_ARCHIVE)
                store.root.mkdir(parents=True, exist_ok=True)
                with tempfile.NamedTemporaryFile(prefix=".upload-", suffix=".zip", dir=store.root, delete=False) as output:
                    temporary = Path(output.name)
                    while remaining:
                        chunk = self.rfile.read(min(1024**2, remaining))
                        if not chunk:
                            raise ReplicaError("Incomplete ZIP upload")
                        output.write(chunk)
                        remaining -= len(chunk)
                return self.send_json(import_capture(temporary, store.root), 201)
            if len(parts) == 4 and parts[:2] == ["api", "projects"]:
                if parts[3] == "reconstruct":
                    return self.send_json(store.start(parts[2], self.json_body()), 202)
                if parts[3] == "measurements":
                    return self.send_json(store.add_measurement(parts[2], self.json_body()), 201)
            raise FileNotFoundError("API route not found")
        except FileNotFoundError as exc:
            self.send_json({"error": str(exc)}, 404)
        except ReplicaError as exc:
            # Do not reuse a connection with an unread/invalid body.
            self.close_connection = True
            self.send_json({"error": str(exc)}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            self.close_connection = True
            self.send_json({"error": str(exc)}, 500)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, default=DEFAULT_STORE)
    parser.add_argument("--colmap", help="Portable COLMAP folder or executable; defaults to COLMAP_PATH")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("doctor")
    importer = subparsers.add_parser("import")
    importer.add_argument("zip", type=Path)
    reconstructor = subparsers.add_parser("reconstruct")
    reconstructor.add_argument("project_id")
    reconstructor.add_argument("--epoch")
    reconstructor.add_argument("--max-image-size", type=int, default=3200)
    packet = subparsers.add_parser("evidence")
    packet.add_argument("project_id")
    validation = subparsers.add_parser("validate")
    validation.add_argument("project_id")
    resume = subparsers.add_parser("resume")
    resume.add_argument("project_id")
    resume.add_argument("--poisson-trim", type=float, default=5)
    server = subparsers.add_parser("serve")
    server.add_argument("--port", type=int, default=5190)
    server.add_argument("--static", type=Path, default=REPO / "apps" / "object-review" / "dist")
    args = parser.parse_args(argv)
    try:
        if args.command == "doctor":
            result = doctor(args.colmap, args.store)
        elif args.command == "import":
            result = import_capture(args.zip, args.store)
        elif args.command == "reconstruct":
            options = {"maxImageSize": args.max_image_size}
            if args.epoch is not None:
                options["epoch"] = args.epoch
            result = ReplicaStore(args.store, args.colmap).start(args.project_id, options, background=False)
        elif args.command == "evidence":
            result = evidence(args.store, args.project_id)
        elif args.command == "validate":
            result = ReplicaStore(args.store, args.colmap).validate(args.project_id)
        elif args.command == "resume":
            result = ReplicaStore(args.store, args.colmap).resume(args.project_id, args.poisson_trim)
        else:
            if not 1024 <= args.port <= 65535:
                raise ReplicaError("Choose a local port from 1024 to 65535")
            httpd = ReplicaHTTPServer(("127.0.0.1", args.port), ReplicaStore(args.store, args.colmap), args.static)
            print(f"Local object review: http://127.0.0.1:{args.port}\nPrivate store: {args.store}", flush=True)
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                httpd.server_close()
            return 0
        print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        if args.command in ("reconstruct", "resume") and result["status"] != "succeeded":
            return 1
        if args.command == "validate" and result.get("validation", {}).get("status") not in ("succeeded", "not-applicable"):
            return 1
        return 0
    except (ReplicaError, FileNotFoundError, OSError) as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
