package com.pixelreplica.capture;

import org.json.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class ScanStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final byte[] original={(byte)255,(byte)216,17,0,45,(byte)255,(byte)217};
    private JSONObject observation(String epoch,String role) throws Exception {
        return new JSONObject().put("epoch",epoch).put("timestampNs","123456789012345")
                .put("width",4032).put("height",3024).put("role",role)
                .put("cameraToWorld",JSONObject.NULL).put("poseStatus","unavailable").put("poseDeltaNs",JSONObject.NULL)
                .put("intrinsics",JSONObject.NULL).put("calibrationStatus","unavailable").put("cameraId","0")
                .put("quality",new JSONObject()).put("controls",new JSONObject());
    }
    @Test public void exportedArchivePreservesOriginalsHashesAndHoldoutRoles() throws Exception {
        ScanStore store=ScanStore.create(temporary.newFolder("sessions"),"LEGO car",new JSONObject().put("model","test"));
        store.commit(original,observation("upright","reconstruct")); store.commit(original,observation("underside","holdout"));
        store.measure("length",145.2,.5,"scale"); store.measure("width",60,.2,"check"); store.complete();
        File archive=store.export(temporary.newFolder("exports")); Map<String,byte[]> files=new HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))) { ZipEntry entry; while((entry=zip.getNextEntry())!=null) files.put(entry.getName(),zip.readAllBytes()); }
        assertArrayEquals(original,files.get("photos/000001.jpg"));
        JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("pixel-object-capture/1",manifest.getString("format")); assertEquals("complete",manifest.getString("status"));
        JSONArray photos=new JSONArray(new String(files.get("photos/photos.json"),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("holdout",photos.getJSONObject(1).getString("role")); assertEquals("underside",photos.getJSONObject(1).getString("epoch"));
        assertTrue(photos.getJSONObject(0).get("timestampNs") instanceof String);
        JSONObject checksums=new JSONObject(new String(files.get("checksums.json"),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(files.size()-1,checksums.length()); assertFalse(checksums.has("checksums.json"));
        Iterator<String> keys=checksums.keys(); while(keys.hasNext()) { String key=keys.next();
            StringBuilder hex=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(files.get(key))) hex.append(String.format(Locale.US,"%02x",b&255));
            assertEquals(hex.toString(),checksums.getString(key));
        }
    }
    @Test public void recoveryDoesNotOverwriteCrashLeftover() throws Exception {
        ScanStore originalStore=ScanStore.create(temporary.newFolder("sessions"),"car",new JSONObject());
        Files.write(new File(originalStore.directory,"photos/000001.jpg").toPath(),original);
        ScanStore recovered=ScanStore.open(originalStore.directory);
        assertEquals("000002",recovered.commit(original,observation("restart","reconstruct")));
        assertArrayEquals(original,Files.readAllBytes(new File(recovered.directory,"photos/000001.jpg").toPath()));
        assertEquals("interrupted",ScanStore.open(originalStore.directory).status());
    }
    @Test public void invalidObservationCannotPublishAnImage() throws Exception {
        ScanStore store=ScanStore.create(temporary.newFolder("sessions"),"car",new JSONObject());
        JSONObject invalid=observation("a","reconstruct").put("poseStatus","matched");
        try {store.commit(original,invalid); fail("Expected invalid pose rejection");} catch(IllegalArgumentException expected){}
        assertEquals(0,store.count()); assertFalse(new File(store.directory,"photos/000001.jpg").exists());
    }
    @Test public void rejectInvalidPhysicalMeasurementsAndEmptyCompletion() throws Exception {
        ScanStore store=ScanStore.create(temporary.newFolder("sessions"),"car",new JSONObject());
        try {store.measure("width",-1,0,"scale"); fail();} catch(IllegalArgumentException expected){}
        try {store.measure("width",1,-.1,"check"); fail();} catch(IllegalArgumentException expected){}
        try {store.complete(); fail();} catch(IllegalStateException expected){}
        assertEquals(0,store.measurementCount());
    }
    @Test public void exportsHaveStableSnapshotIdentityAndChangedEvidenceGetsNewIdentity() throws Exception {
        ScanStore store=ScanStore.create(temporary.newFolder("sessions"),"car",new JSONObject());
        store.commit(original,observation("first","reconstruct")); store.complete();
        File exports=temporary.newFolder("exports"); File first=store.export(exports); String originalHash=ScanStore.sha256(first);
        assertEquals(first,store.export(exports));
        String sessionId=ScanStore.readObject(new File(store.directory,"manifest.json")).getString("id");
        store.measure("length",123,.5,"scale"); File second=store.export(exports);
        assertNotEquals(first,second); assertEquals(originalHash,ScanStore.sha256(first));
        try(ZipFile zip=new ZipFile(second)) {
            JSONObject snapshot=new JSONObject(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(sessionId,snapshot.getString("sourceSessionId")); assertNotEquals(sessionId,snapshot.getString("id"));
            assertEquals(64,snapshot.getString("snapshotPayloadSha256").length());
        }
    }
}
