"""Boundary and local workflow tests. Synthetic images never establish phone fidelity."""
import hashlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4
import zipfile

import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("replica", Path(__file__).with_name("replica.py"))
replica = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replica)


def jpeg(width=96, height=72):
    target = io.BytesIO()
    Image.new("RGB", (width, height), (140, 35, 20)).save(target, "JPEG", quality=95)
    return target.getvalue()


def bundle_files(project_id=None, count=5):
    project_id = project_id or str(uuid4())
    manifest = {"format": replica.FORMAT, "id": project_id, "name": "Synthetic boundary fixture", "createdAt": "2026-10-22T12:00:00Z",
                "device": {"model": "not a phone"}, "status": "complete", "coordinateSystem": replica.COORDINATES}
    observations = []
    files = {"manifest.json": json.dumps(manifest).encode(), "measurements.json": b"[]"}
    for number in range(count):
        photo_id = f"{number:06}"
        relative = f"photos/{photo_id}.jpg"
        files[relative] = jpeg()
        observations.append({"id": photo_id, "file": relative, "epoch": "upright-1", "timestampNs": str(10**15+number), "width": 96, "height": 72,
                             "role": "holdout" if number == count-1 else "reconstruct", "cameraToWorld": None, "poseStatus": "unavailable",
                             "poseDeltaNs": None, "intrinsics": None, "calibrationStatus": "unavailable", "cameraId": "0", "quality": {}, "controls": {}})
    files["photos/photos.json"] = json.dumps(observations).encode()
    return project_id, files


def write_bundle(path, files, checksum=True):
    files = dict(files)
    if checksum:
        files["checksums.json"] = json.dumps({name: hashlib.sha256(data).hexdigest() for name, data in files.items()}).encode()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = self.root / "store"
        self.archive = self.root / "capture.zip"
        self.project_id, self.files = bundle_files()

    def tearDown(self):
        self.temporary.cleanup()

    def import_fixture(self):
        write_bundle(self.archive, self.files)
        return replica.import_capture(self.archive, self.store)

    def mutate_observation(self, callback):
        observations = json.loads(self.files["photos/photos.json"])
        callback(observations)
        self.files["photos/photos.json"] = json.dumps(observations).encode()

    def test_import_preserves_original_bytes_and_is_idempotent(self):
        project = self.import_fixture()
        self.assertEqual(project["id"], self.project_id)
        self.assertEqual(project["scale"]["status"], "unscaled")
        for name, data in self.files.items():
            self.assertEqual((self.store / self.project_id / "original" / name).read_bytes(), data)
        self.assertEqual(replica.import_capture(self.archive, self.store)["id"], self.project_id)
        self.assertEqual(len(replica.list_projects(self.store)), 1)

    def test_different_bytes_same_capture_id_do_not_replace_originals(self):
        self.import_fixture()
        original_hash = replica.sha256(self.store / self.project_id / "original.zip")
        manifest = json.loads(self.files["manifest.json"])
        manifest["name"] = "Changed"
        self.files["manifest.json"] = json.dumps(manifest).encode()
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "already exists"):
            replica.import_capture(self.archive, self.store)
        self.assertEqual(replica.sha256(self.store / self.project_id / "original.zip"), original_hash)

    def test_same_payload_in_a_different_zip_container_is_idempotent(self):
        self.import_fixture()
        original_hash = replica.sha256(self.archive)
        with zipfile.ZipFile(self.archive) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        with zipfile.ZipFile(self.archive, "w", zipfile.ZIP_STORED) as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        self.assertNotEqual(replica.sha256(self.archive), original_hash)
        self.assertEqual(replica.import_capture(self.archive, self.store)["id"], self.project_id)
        self.assertEqual(replica.sha256(self.store / self.project_id / "original.zip"), original_hash)

    def test_path_escape_rejected_before_extraction(self):
        self.files["../escaped.txt"] = b"unsafe"
        write_bundle(self.archive, self.files)
        with self.assertRaises(replica.ReplicaError):
            replica.import_capture(self.archive, self.store)
        self.assertFalse((self.root / "escaped.txt").exists())

    def test_windows_alias_path_rejected(self):
        for path in ("photos/NUL.jpg", "C:/absolute.txt", "photos\\one.jpg", "/absolute", "x/../y", "one. /a"):
            with self.subTest(path=path), self.assertRaises(replica.ReplicaError):
                replica.safe_relative(path)

    def test_case_colliding_entries_rejected(self):
        self.files["PHOTOS/000000.JPG"] = jpeg()
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "case-colliding"):
            replica.import_capture(self.archive, self.store)

    def test_duplicate_zip_entry_rejected(self):
        write_bundle(self.archive, self.files)
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("manifest.json", self.files["manifest.json"])
        with self.assertRaisesRegex(replica.ReplicaError, "duplicate"):
            replica.import_capture(self.archive, self.store)

    def test_link_rejected(self):
        write_bundle(self.archive, self.files)
        with zipfile.ZipFile(self.archive, "a") as archive:
            entry = zipfile.ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, "manifest.json")
        with self.assertRaisesRegex(replica.ReplicaError, "links"):
            replica.import_capture(self.archive, self.store)

    def test_expansion_bomb_rejected(self):
        self.files["depth/bomb.bin"] = b"x" * (2*1024**2)
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "expansion"):
            replica.import_capture(self.archive, self.store)

    def test_checksum_mismatch_rejected(self):
        write_bundle(self.archive, self.files)
        # Rewrite one member while preserving the original checksum map.
        with zipfile.ZipFile(self.archive) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        files["photos/000000.jpg"] = jpeg(97, 72)
        write_bundle(self.archive, files, checksum=False)
        with self.assertRaisesRegex(replica.ReplicaError, "Checksum mismatch"):
            replica.import_capture(self.archive, self.store)

    def test_unhashed_files_rejected(self):
        write_bundle(self.archive, self.files)
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("unhashed.txt", b"extra")
        with self.assertRaisesRegex(replica.ReplicaError, "cover every"):
            replica.import_capture(self.archive, self.store)

    def test_jpeg_dimensions_are_checked_against_sensor_metadata(self):
        self.mutate_observation(lambda obs: obs[0].update(width=999))
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "dimensions"):
            replica.import_capture(self.archive, self.store)

    def test_png_masquerading_as_jpeg_is_rejected(self):
        target = io.BytesIO()
        Image.new("RGB", (96, 72)).save(target, "PNG")
        self.files["photos/000000.jpg"] = target.getvalue()
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "JPEG"):
            replica.import_capture(self.archive, self.store)

    def test_duplicate_photo_id_rejected(self):
        self.mutate_observation(lambda obs: obs[1].update(id=obs[0]["id"]))
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "unique"):
            replica.import_capture(self.archive, self.store)

    def test_nonrigid_pose_rejected(self):
        pose = np.eye(4)
        pose[0, 0] = 2
        self.mutate_observation(lambda obs: obs[0].update(cameraToWorld=pose.flatten(order="F").tolist(), poseStatus="matched"))
        write_bundle(self.archive, self.files)
        with self.assertRaisesRegex(replica.ReplicaError, "rigid"):
            replica.import_capture(self.archive, self.store)

    def test_holdouts_are_not_selected_for_reconstruction(self):
        self.import_fixture()
        run = replica.ReplicaStore(self.store).prepare_run(self.project_id)
        self.assertEqual(run["photoCount"], 4)
        self.assertEqual(run["holdoutCount"], 1)
        self.assertNotIn("000004", run["selectedPhotoIds"])
        self.assertEqual(run["heldoutPhotoIds"], ["000004"])

    def test_mixed_epochs_require_selection(self):
        self.mutate_observation(lambda obs: obs[0].update(epoch="other"))
        self.import_fixture()
        store = replica.ReplicaStore(self.store)
        with self.assertRaisesRegex(replica.ReplicaError, "exactly one"):
            store.prepare_run(self.project_id)
        run = store.prepare_run(self.project_id, {"epoch": "upright-1"})
        self.assertEqual(run["photoCount"], 3)

    def test_missing_engine_is_recorded_as_failed_not_fake_success(self):
        self.import_fixture()
        store = replica.ReplicaStore(self.store, self.root / "absent-colmap")
        run = store.start(self.project_id, background=False)
        self.assertEqual(run["status"], "failed")
        self.assertIn("COLMAP", run["error"])
        self.assertNotIn("model", run)
        for name, data in self.files.items():
            self.assertEqual((self.store / self.project_id / "original" / name).read_bytes(), data)

    def test_scale_check_does_not_refit_scale(self):
        self.import_fixture()
        store = replica.ReplicaStore(self.store)
        run = store.prepare_run(self.project_id)
        # Measurement API unit fixture only, explicitly not a reconstruction result.
        run["status"] = "succeeded"
        replica.write_json(self.store / self.project_id / "runs" / run["id"] / "result.json", run)
        first = store.add_measurement(self.project_id, {"label": "reference", "valueMm": 100, "role": "scale", "a": [0,0,0], "b": [2,0,0]})
        self.assertAlmostEqual(first["scale"]["metersPerUnit"], 0.05)
        checked = store.add_measurement(self.project_id, {"label": "independent", "valueMm": 49, "role": "check", "a": [0,0,0], "b": [0,1,0]})
        self.assertAlmostEqual(checked["scale"]["metersPerUnit"], 0.05)
        self.assertAlmostEqual(checked["measurements"][-1]["residualMm"], 1)

    def test_failed_run_cannot_be_given_metric_measurements(self):
        self.import_fixture()
        store = replica.ReplicaStore(self.store)
        store.prepare_run(self.project_id)
        with self.assertRaisesRegex(replica.ReplicaError, "succeeded"):
            store.add_measurement(self.project_id, {"label": "length", "valueMm": 100, "role": "scale", "a": [0,0,0], "b": [2,0,0]})

    def test_engine_lock_prevents_a_second_store_instance_from_racing(self):
        first = replica.EngineLock(self.store)
        try:
            with self.assertRaisesRegex(replica.ReplicaError, "already owns"):
                replica.EngineLock(self.store)
        finally:
            first.close()
        second = replica.EngineLock(self.store)
        second.close()

    def test_restart_recovers_abandoned_job_without_stomping_a_live_lock(self):
        self.import_fixture()
        store = replica.ReplicaStore(self.store)
        run = store.prepare_run(self.project_id)
        live_lock = replica.EngineLock(self.store)
        try:
            replica.ReplicaStore(self.store)
            self.assertEqual(replica.get_project(self.store, self.project_id)["latestRun"]["status"], "queued")
        finally:
            live_lock.close()
        replica.ReplicaStore(self.store)
        recovered = replica.get_project(self.store, self.project_id)["latestRun"]
        self.assertEqual(recovered["id"], run["id"])
        self.assertEqual(recovered["status"], "failed")
        self.assertEqual(recovered["stage"], "interrupted")

    def test_evidence_is_local_and_preserves_holdout_roles(self):
        self.import_fixture()
        packet = replica.evidence(self.store, self.project_id)
        report = replica.read_json(Path(packet["path"]) / "report.json")
        self.assertEqual(report["project"]["photos"][-1]["role"], "holdout")
        self.assertTrue((Path(packet["path"]) / "contact-sheet-001.jpg").is_file())
        self.assertIn("No files", report["privacy"])
        self.assertIn("Do not upload", (Path(packet["path"]) / "agent-prompt.md").read_text())
        prompt = (Path(packet["path"]) / "agent-prompt.md").read_text()
        self.assertIn(str((Path(packet["path"]) / "report.json").resolve()), prompt)
        self.assertIn(str((self.store / self.project_id / "original").resolve()), prompt)

    def test_evidence_rejects_modified_original(self):
        self.import_fixture()
        (self.store / self.project_id / "original" / "photos" / "000000.jpg").write_bytes(jpeg(97,72))
        with self.assertRaisesRegex(replica.ReplicaError, "Checksum mismatch"):
            replica.evidence(self.store, self.project_id)

    def test_http_import_list_asset_and_origin_boundaries(self):
        write_bundle(self.archive, self.files)
        static = self.root / "static"
        static.mkdir()
        (static / "index.html").write_text("Local test UI")
        server = replica.ReplicaHTTPServer(("127.0.0.1", 0), replica.ReplicaStore(self.store), static)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_port
        def request(method, path, body=None, headers=None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            result = response.status, response.read(), dict(response.headers)
            connection.close()
            return result
        try:
            status, raw, _ = request("POST", "/api/import", self.archive.read_bytes(), {"Content-Type": "application/zip"})
            self.assertEqual(status, 201, raw)
            status, raw, _ = request("GET", "/api/projects")
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(raw)[0]["id"], self.project_id)
            status, raw, headers = request("GET", f"/api/projects/{self.project_id}/files/original/photos/000000.jpg")
            self.assertEqual(status, 200)
            self.assertEqual(raw, self.files["photos/000000.jpg"])
            self.assertEqual(headers["Content-Type"], "image/jpeg")
            status, _, _ = request("GET", "/api/projects", headers={"Origin": "https://untrusted.example"})
            self.assertEqual(status, 400)
            status, _, _ = request("GET", "/api/projects", headers={"Host": "untrusted.example"})
            self.assertEqual(status, 400)
            status, _, _ = request("GET", f"/api/projects/{self.project_id}/files/../../project.json")
            self.assertEqual(status, 400)
            status, _, _ = request("GET", "/api/does-not-exist")
            self.assertEqual(status, 404)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class CameraTests(unittest.TestCase):
    def test_atomic_json_write_uses_a_short_name_at_a_long_windows_path(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            while len(str(parent)) < 230:
                parent = parent / "nested-dir"
            parent.mkdir(parents=True)
            target = parent / "validation.json"
            replica.write_json(target, {"valid": True})
            self.assertEqual(replica.read_json(target), {"valid": True})
            self.assertEqual(list(parent.glob("*.tmp")), [])

    def test_localization_failure_keeps_the_existing_surface_available(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = {"id": "unit-state-fixture", "status": "succeeded", "warnings": []}
            with patch.object(replica, "localize_holdouts", side_effect=OSError("native localization unavailable")):
                replica.finish_validation(root, root, run, None)
            self.assertEqual(run["status"], "succeeded")
            self.assertEqual(run["validation"]["status"], "failed")
            self.assertIn("independent photo comparison is not established", run["warnings"][-1])

    def test_production_mutation_is_a_hard_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = {"id": "unit-state-fixture", "status": "succeeded", "warnings": []}
            with patch.object(replica, "localize_holdouts", side_effect=replica.ArtifactMutationError("changed production")):
                with self.assertRaises(replica.ArtifactMutationError):
                    replica.finish_validation(root, root, run, None)
            self.assertEqual(replica.read_json(root / "result.json")["status"], "failed")

    def test_deleted_protected_file_is_a_persisted_hard_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            protected = root / "mesh.ply"
            protected.write_bytes(b"protected geometry bytes")
            original = replica.protected_hashes(root, ["mesh.ply"])
            run = {"id": "unit-state-fixture", "status": "succeeded", "warnings": []}
            def delete_then_audit(*args):
                protected.unlink()
                replica.protected_hashes(root, original)
            with patch.object(replica, "localize_holdouts", side_effect=delete_then_audit):
                with self.assertRaisesRegex(replica.ArtifactMutationError, "missing or unreadable"):
                    replica.finish_validation(root, root, run, None)
            result = replica.read_json(root / "result.json")
            self.assertEqual(result["status"], "failed")
            self.assertFalse(result["validation"]["productionHashesUnchanged"])

    def test_unreadable_protected_file_is_integrity_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(replica, "sha256", side_effect=PermissionError("read denied")):
                with self.assertRaisesRegex(replica.ArtifactMutationError, "missing or unreadable"):
                    replica.protected_hashes(Path(directory), ["texture.png"])

    def test_holdout_validation_rejects_moving_geometry_or_existing_cameras(self):
        points = {1: [1., 2., 3.]}
        cameras = [{"photoId": "source", "cameraToWorld": np.eye(4).flatten(order="F").tolist()}]
        replica.verify_fixed_geometry(points, dict(points), cameras, cameras)
        with self.assertRaisesRegex(replica.ReplicaError, "point geometry"):
            replica.verify_fixed_geometry(points, {1: [1., 2., 3.1]}, cameras, cameras)
        changed_camera = dict(cameras[0])
        changed_camera["cameraToWorld"] = list(changed_camera["cameraToWorld"])
        changed_camera["cameraToWorld"][12] = 0.1
        with self.assertRaisesRegex(replica.ReplicaError, "camera"):
            replica.verify_fixed_geometry(points, points, cameras, [changed_camera])

    def test_colmap_pose_conversion_keeps_projection_and_center(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cameras.txt").write_text("# camera\n1 PINHOLE 640 480 500 510 320 240\n")
            (root / "images.txt").write_text("# image\n1 1 0 0 0 -1 -2 -3 1 a.jpg\n\n")
            camera = replica.parse_colmap_cameras(root, {"a.jpg":"one"})[0]
            matrix = np.array(camera["cameraToWorld"]).reshape((4,4), order="F")
            self.assertTrue(np.allclose(matrix[:3,3], [1,2,3]))
            world_point = np.array([1.1,2.2,4,1])
            gl_point = np.linalg.inv(matrix) @ world_point
            self.assertAlmostEqual(gl_point[0]/-gl_point[2]*500+320, 370)
            self.assertAlmostEqual(-gl_point[1]/-gl_point[2]*510+240, 342)
            self.assertEqual(camera["photoId"], "one")

    def test_blank_point_line_does_not_skip_next_camera(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cameras.txt").write_text("1 SIMPLE_RADIAL 640 480 500 320 240 0.01\n")
            (root / "images.txt").write_text("1 1 0 0 0 0 0 0 1 a.jpg\n\n2 1 0 0 0 1 0 0 1 b.jpg\n\n")
            self.assertEqual(len(replica.parse_colmap_cameras(root, {})), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
