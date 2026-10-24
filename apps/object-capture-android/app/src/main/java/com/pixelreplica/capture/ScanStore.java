package com.pixelreplica.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Owns the capture contract. Originals are committed before their observations become visible. */
public final class ScanStore {
    public final File directory;
    private JSONObject manifest;
    private JSONArray photos;
    private JSONArray measurements;

    public static ScanStore create(File sessions, String name, JSONObject device) throws Exception {
        if (name.trim().isEmpty()) throw new IllegalArgumentException("Name is required");
        String id = UUID.randomUUID().toString();
        File directory = new File(sessions, id);
        if (!new File(directory, "photos").mkdirs()) throw new IOException("Cannot create session");
        JSONObject coordinates = new JSONObject().put("units", "meters")
                .put("cameraToWorld", "column-major-4x4")
                .put("cameraAxes", "+X right,+Y up,-Z forward")
                .put("worldAxes", "right-handed,+Y up");
        JSONObject manifest = new JSONObject().put("format", "pixel-object-capture/1")
                .put("id", id).put("name", name.trim()).put("createdAt", Instant.now().toString())
                .put("device", device).put("status", "interrupted").put("coordinateSystem", coordinates)
                .put("currentObjectPass", "pass-1")
                .put("notes", "Photogrammetry evidence; size, colour and fine-detail fidelity require independent checks.");
        ScanStore store = new ScanStore(directory, manifest, new JSONArray(), new JSONArray());
        store.persist(); return store;
    }

    public static ScanStore open(File directory) throws Exception {
        return new ScanStore(directory, readObject(new File(directory, "manifest.json")),
                new JSONArray(readText(new File(directory, "photos/photos.json"))),
                new JSONArray(readText(new File(directory, "measurements.json"))));
    }

    private ScanStore(File directory, JSONObject manifest, JSONArray photos, JSONArray measurements) {
        this.directory = directory; this.manifest = manifest; this.photos = photos; this.measurements = measurements;
    }

    public synchronized String name() { return manifest.optString("name"); }
    public synchronized int count() { return photos.length(); }
    public synchronized int measurementCount() { return measurements.length(); }
    public synchronized JSONArray photos() throws JSONException { return new JSONArray(photos.toString()); }
    public synchronized JSONArray measurements() throws JSONException { return new JSONArray(measurements.toString()); }
    public synchronized String status() { return manifest.optString("status"); }

    /** The object placement new photos belong to. Unlike epoch, it survives camera restarts. */
    public synchronized String objectPass() { return manifest.optString("currentObjectPass", "pass-1"); }

    /** Starts a pass after the object is moved or turned over; an empty current pass is reused. */
    public synchronized String startNewPass() throws Exception {
        String current = objectPass();
        int highest = passNumber(current);
        boolean currentHasPhotos = false;
        for (int i = 0; i < photos.length(); i++) {
            String pass = photos.getJSONObject(i).optString("objectPass", "");
            highest = Math.max(highest, passNumber(pass));
            currentHasPhotos |= pass.equals(current);
        }
        if (!currentHasPhotos) return current;
        String next = "pass-" + (highest + 1);
        manifest.put("currentObjectPass", next);
        atomicJson(new File(directory, "manifest.json"), manifest);
        return next;
    }

    private static int passNumber(String pass) {
        try { return pass.startsWith("pass-") ? Integer.parseInt(pass.substring(5)) : 0; }
        catch (NumberFormatException notNumbered) { return 0; }
    }

    public synchronized String commit(byte[] jpeg, JSONObject observation) throws Exception {
        if (jpeg.length < 4 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216)
            throw new IllegalArgumentException("Expected JPEG bytes");
        // A crash may leave an unindexed original. Never overwrite that original on recovery.
        int next = photos.length() + 1;
        while (new File(directory, String.format(Locale.US, "photos/%06d.jpg", next)).exists()) next++;
        String id = String.format(Locale.US, "%06d", next);
        String relative = "photos/" + id + ".jpg";
        JSONObject record = new JSONObject(observation.toString()).put("id", id).put("file", relative);
        validateObservation(record);
        atomic(new File(directory, relative), jpeg);
        JSONArray updated = new JSONArray(photos.toString()); updated.put(record);
        atomicJson(new File(directory, "photos/photos.json"), updated);
        photos = updated;
        manifest.put("status", "interrupted");
        atomicJson(new File(directory, "manifest.json"), manifest);
        return id;
    }

    public synchronized void measure(String label, double valueMm, double uncertaintyMm, String role) throws Exception {
        if (label.trim().isEmpty() || !Double.isFinite(valueMm) || valueMm <= 0
                || !Double.isFinite(uncertaintyMm) || uncertaintyMm < 0
                || !(role.equals("scale") || role.equals("check"))) throw new IllegalArgumentException("Invalid measurement");
        JSONArray updated = new JSONArray(measurements.toString());
        updated.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("label", label.trim())
                .put("valueMm", valueMm).put("uncertaintyMm", uncertaintyMm).put("role", role));
        atomicJson(new File(directory, "measurements.json"), updated); measurements = updated;
    }

    public synchronized void complete() throws Exception {
        if (photos.length() == 0) throw new IllegalStateException("Capture at least one photo first");
        manifest.put("status", "complete").put("endedAt", Instant.now().toString()); persist();
    }

    public synchronized File export(File exports) throws Exception {
        if (!exports.isDirectory() && !exports.mkdirs()) throw new IOException("Cannot create export folder");
        // Export only indexed originals; crash leftovers remain recoverable on the phone.
        List<String> paths = new ArrayList<>(Arrays.asList("manifest.json", "photos/photos.json", "measurements.json"));
        for (int i = 0; i < photos.length(); i++) paths.add(photos.getJSONObject(i).getString("file"));
        Collections.sort(paths);
        JSONObject checksums = new JSONObject();
        StringBuilder payloadTable = new StringBuilder("pixel-object-snapshot/1\n");
        for (String relative : paths) {
            String hash=sha256(new File(directory, relative)); checksums.put(relative,hash);
            payloadTable.append(relative).append('\t').append(hash).append('\n');
        }
        String payloadHash=sha256(payloadTable.toString().getBytes(StandardCharsets.UTF_8));
        String snapshotId=UUID.nameUUIDFromBytes(payloadHash.getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject snapshotManifest=new JSONObject(manifest.toString()).put("id",snapshotId)
                .put("sourceSessionId",manifest.getString("id")).put("snapshotPayloadSha256",payloadHash);
        byte[] manifestBytes=snapshotManifest.toString().getBytes(StandardCharsets.UTF_8);
        checksums.put("manifest.json",sha256(manifestBytes));
        String safeName = name().replaceAll("[^A-Za-z0-9_-]+", "-");
        if (safeName.length() > 45) safeName = safeName.substring(0, 45);
        File archive = new File(exports, safeName + "-" + snapshotId + ".zip");
        if(archive.isFile()) return archive; // Unchanged payload is the same immutable export snapshot.
        File temporary = new File(archive.getPath() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
            for (String relative : paths) {
                ZipEntry entry=new ZipEntry(relative); entry.setTime(315532800000L); zip.putNextEntry(entry);
                if(relative.equals("manifest.json")) zip.write(manifestBytes);
                else Files.copy(new File(directory, relative).toPath(), zip);
                zip.closeEntry();
            }
            ZipEntry hashEntry=new ZipEntry("checksums.json"); hashEntry.setTime(315532800000L); zip.putNextEntry(hashEntry);
            zip.write(checksums.toString(2).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Files.move(temporary.toPath(), archive.toPath()); return archive;
    }

    static void validateObservation(JSONObject o) throws Exception {
        for (String field : Arrays.asList("epoch", "timestampNs", "width", "height", "role", "cameraToWorld", "poseStatus",
                "poseDeltaNs", "intrinsics", "calibrationStatus", "cameraId", "quality", "controls"))
            if (!o.has(field)) throw new IllegalArgumentException("Missing " + field);
        if (o.getString("epoch").trim().isEmpty() || Long.parseLong(o.getString("timestampNs")) <= 0
                || o.getInt("width") <= 0 || o.getInt("height") <= 0) throw new IllegalArgumentException("Invalid observation");
        if (!Arrays.asList("reconstruct", "holdout").contains(o.getString("role"))) throw new IllegalArgumentException("Invalid role");
        if (o.has("objectPass") && o.getString("objectPass").trim().isEmpty()) throw new IllegalArgumentException("Invalid object pass");
        if (!Arrays.asList("matched", "nearest", "unavailable").contains(o.getString("poseStatus"))) throw new IllegalArgumentException("Invalid pose status");
        if (!o.isNull("cameraToWorld")) {
            JSONArray matrix = o.getJSONArray("cameraToWorld");
            if (matrix.length() != 16) throw new IllegalArgumentException("Expected 4x4 matrix");
            for (int i = 0; i < 16; i++) if (!Double.isFinite(matrix.getDouble(i))) throw new IllegalArgumentException("Invalid matrix");
        } else if (!o.getString("poseStatus").equals("unavailable")) throw new IllegalArgumentException("Missing pose matrix");
        if (!Arrays.asList("calibrated", "approximate", "unavailable").contains(o.getString("calibrationStatus")))
            throw new IllegalArgumentException("Invalid calibration status");
    }

    private synchronized void persist() throws Exception {
        atomicJson(new File(directory, "manifest.json"), manifest);
        atomicJson(new File(directory, "photos/photos.json"), photos);
        atomicJson(new File(directory, "measurements.json"), measurements);
    }
    static String readText(File path) throws Exception { return new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8); }
    static JSONObject readObject(File path) throws Exception { return new JSONObject(readText(path)); }
    static void atomicJson(File path, Object object) throws Exception { atomic(path, object.toString().getBytes(StandardCharsets.UTF_8)); }
    static void atomic(File path, byte[] data) throws Exception {
        File tmp = new File(path.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 255)); return hex.toString();
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.US,"%02x",b&255)); return hex.toString();
    }
}
