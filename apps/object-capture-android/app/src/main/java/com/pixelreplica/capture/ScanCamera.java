package com.pixelreplica.capture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import com.google.ar.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Single camera owner. All platform callbacks are hidden behind capture and lifecycle operations. */
final class ScanCamera implements GLSurfaceView.Renderer {
    interface Listener {
        void status(String text, boolean ready);
        void saved(String id);
        void error(String text);
    }
    private final Activity activity;
    private final ScanStore store;
    private final Listener listener;
    final GLSurfaceView view;
    private final CameraBackground background = new CameraBackground();
    private final HandlerThread cameraThread = new HandlerThread("object-camera");
    private final Handler handler;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final PoseHistory poses = new PoseHistory();
    private final CaptureState state = new CaptureState();
    private final FocusPolicy focus = new FocusPolicy();
    private final Object lifecycle = new Object();
    private volatile Session ar;
    private SharedCamera shared;
    private CameraDevice device;
    private CameraCaptureSession captures;
    private ImageReader jpegReader;
    private CameraCharacteristics characteristics;
    private String cameraId = "";
    private volatile boolean active, closed, opening, resumed;
    private volatile String epoch = epoch();
    private volatile boolean autoCapture, holdout, freezeColor;
    private volatile boolean cpuFallback;
    private int width = 1, height = 1, jpegAttempt;
    private List<Size> jpegSizes = new ArrayList<>();
    private volatile Size selectedJpeg;
    private volatile FrameEvidence evidence;
    private volatile CaptureQuality quality;
    private volatile long qualityTimeNs;
    private volatile Pending pending;
    private volatile TotalCaptureResult lastResult, fixedControls;
    private volatile boolean releasingFocus, focusResetFailed;
    private volatile long focusResetAfterSensorNs;
    private long lastStatusMs, previousFrameTime;
    private float[] previousPose;
    private int stableFrames;
    private volatile boolean cpuCaptureRequested;
    private final LinkedHashMap<Long,JpegFrame> receivedJpegs = new LinkedHashMap<>();
    private record JpegFrame(byte[] bytes,int width,int height) {}
    private record FocusCommand(Pending shot,boolean cancel) {}

    private record FrameEvidence(long timestampNs, String epoch, float[] matrix, float[] intrinsics,
                                 int width, int height, String tracking, boolean stable) {}
    private static final class Pending {
        final String epoch, role;
        final FrameEvidence trigger;
        final CaptureQuality previewQuality;
        final boolean cpuSource, freezeRequested;
        final String cameraId;
        final CameraCharacteristics characteristics;
        byte[] jpeg;
        long imageTimestamp;
        int width, height;
        TotalCaptureResult result;
        boolean saving;
        long requestedElapsedNs;
        volatile boolean waitingForFocus;
        FocusPolicy.Readiness focusAtTrigger, focusBeforeCapture;
        Integer requestedAfMode;
        String focusWarning;
        FocusLockSequence lockSequence;
        Session originAr;
        CameraCaptureSession originCaptures;
        CameraDevice originDevice;
        SharedCamera originShared;
        volatile boolean waitingForLock, cancelFinished;
        FocusPolicy.Sample startFocusResult, lockedFocus, cancelFocusResult;
        Long startRequestedElapsedNs, cancelRequestedElapsedNs;
        Integer startReportedTrigger, cancelReportedTrigger, requestedControlMode, repeatingCaptureIntent;
        Integer startReportedControlMode,startReportedIntent,cancelReportedControlMode,cancelReportedIntent;
        String cancelStatus="not-requested", cancelReason;
        Pending(String epoch,String role,FrameEvidence trigger,CaptureQuality previewQuality,boolean cpuSource,
                boolean freezeRequested,String cameraId,CameraCharacteristics characteristics) {
            this.epoch=epoch; this.role=role; this.trigger=trigger; this.previewQuality=previewQuality;
            this.cpuSource=cpuSource; this.freezeRequested=freezeRequested; this.cameraId=cameraId; this.characteristics=characteristics;
        }
    }

    ScanCamera(Activity activity, ScanStore store, Listener listener, boolean cpuFallback) {
        this.activity = activity; this.store = store; this.listener = listener; this.cpuFallback = cpuFallback;
        cameraThread.start(); handler = new Handler(cameraThread.getLooper());
        view = new GLSurfaceView(activity); view.setEGLContextClientVersion(2);
        view.setPreserveEGLContextOnPause(true); view.setRenderer(this);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    private static String epoch() { return "pass-" + UUID.randomUUID(); }
    boolean isPending() { return state.pending(); }
    void setAutoCapture(boolean enabled) { autoCapture = enabled; }
    void setHoldout(boolean enabled) { holdout = enabled; }
    void setFreezeColor(boolean enabled) { freezeColor = enabled; fixedControls = enabled ? lastResult : null; }

    void resume() {
        if (closed) return;
        resumed = true; view.onResume();
        if (background.texture != 0) open();
    }
    void pause() {
        resumed = false;
        view.onPause();
        synchronized (lifecycle) {
            closePlatform();
        }
    }
    void close() { pause(); closed = true; writer.shutdown(); cameraThread.quitSafely(); }

    void newEpoch() {
        if (state.pending()) { fail("Wait for the current photo before starting a new pass."); return; }
        pause(); epoch = epoch(); poses.clear(); previousPose = null; evidence = null; stableFrames = 0; resume();
    }

    private void closePlatform() {
        active = false; opening = false;
        if (ar != null) {
            try { ar.pause(); } catch (Exception ignored) { }
        }
        if (captures != null) { captures.close(); captures = null; }
        if (device != null) { device.close(); device = null; }
        if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
        if (ar != null) { ar.close(); ar = null; }
        epoch = epoch(); poses.clear(); evidence = null; previousPose = null; stableFrames = 0;
        focus.clear(); lastResult = null; fixedControls = null;
        releasingFocus=false; focusResetFailed=false; focusResetAfterSensorNs=0;
        Pending shot = pending;
        cpuCaptureRequested = false;
        if (shot != null && !shot.saving) {
            pending = null; state.finish();
            fail("Capture interrupted. Previously saved photos are safe.");
        }
    }

    private void open() {
        if (!resumed || closed || opening || ar != null) return;
        opening = true;
        activity.runOnUiThread(() -> {
            try {
                if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    opening = false; activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, 21); return;
                }
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    opening = false; return;
                }
                synchronized (lifecycle) {
                    if (!resumed || closed) { opening = false; return; }
                    ar = new Session(activity, EnumSet.of(Session.Feature.SHARED_CAMERA));
                    Config config = ar.getConfig(); config.setFocusMode(Config.FocusMode.AUTO);
                    config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
                    // Photo reconstruction is the detail source; no synthetic depth promise.
                    config.setDepthMode(Config.DepthMode.DISABLED); ar.configure(config);
                    if (cpuFallback) {
                        List<CameraConfig> configs = ar.getSupportedCameraConfigs(new CameraConfigFilter(ar)
                                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)));
                        CameraConfig largest = Collections.max(configs, Comparator.comparingLong(c -> (long)c.getImageSize().getWidth() * c.getImageSize().getHeight()));
                        ar.setCameraConfig(largest);
                    }
                    shared = ar.getSharedCamera(); cameraId = ar.getCameraConfig().getCameraId();
                    CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                    characteristics = manager.getCameraCharacteristics(cameraId);
                    jpegSizes = jpegSizes(characteristics); jpegAttempt = 0;
                    ar.setCameraTextureName(background.texture);
                    final Session openedAr = ar;
                    manager.openCamera(cameraId, shared.createARDeviceStateCallback(new CameraDevice.StateCallback() {
                        @Override public void onOpened(CameraDevice camera) {
                            if (!resumed || closed || ar != openedAr) { camera.close(); return; }
                            device = camera; configure();
                        }
                        @Override public void onDisconnected(CameraDevice camera) { camera.close(); if(ar==openedAr) { active = false; fail("Camera disconnected. Return home and resume this session."); } }
                        @Override public void onError(CameraDevice camera, int error) { camera.close(); if(ar==openedAr) { active = false; fail("Camera error " + error + ". Return home and resume this session."); } }
                    }, handler), handler);
                }
            } catch (Exception error) { opening = false; fail("Cannot open camera: " + error.getMessage()); }
        });
    }

    private static List<Size> jpegSizes(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        List<Size> choices = new ArrayList<>();
        if (map != null && map.getOutputSizes(ImageFormat.JPEG) != null)
            for (Size size : map.getOutputSizes(ImageFormat.JPEG))
                if ((long)size.getWidth() * size.getHeight() <= 16_000_000L) choices.add(size);
        choices.sort(Comparator.comparingLong((Size s) -> (long)s.getWidth() * s.getHeight()).reversed());
        return choices;
    }

    private void configure() {
        if (!resumed || closed || device == null || ar == null) return;
        try {
            if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
            ArrayList<Surface> surfaces = new ArrayList<>(shared.getArCoreSurfaces());
            if (!cpuFallback && jpegAttempt < jpegSizes.size()) {
                selectedJpeg = jpegSizes.get(jpegAttempt);
                jpegReader = ImageReader.newInstance(selectedJpeg.getWidth(), selectedJpeg.getHeight(), ImageFormat.JPEG, 2);
                jpegReader.setOnImageAvailableListener(this::jpegAvailable, handler);
                surfaces.add(jpegReader.getSurface());
            } else {
                cpuFallback = true; selectedJpeg = null;
            }
            // JPEG is configured for single still requests, never added to the repeating targets.
            shared.setAppSurfaces(cameraId, Collections.emptyList());
            final Session configuredAr = ar;
            final SharedCamera configuredShared = shared;
            final CameraDevice configuredDevice = device;
            device.createCaptureSession(surfaces, shared.createARSessionStateCallback(new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (!resumed || closed || ar != configuredAr) { session.close(); return; }
                    captures = session;
                    try {
                        CaptureRequest.Builder preview = configuredDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        for (Surface surface : configuredShared.getArCoreSurfaces()) preview.addTarget(surface);
                        preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        // ARCore is paused here. It owns repeating requests after onActive/resume.
                        captures.setRepeatingRequest(preview.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
                    } catch (Exception error) { fail("Cannot start preview: " + error.getMessage()); }
                }
                @Override public void onActive(CameraCaptureSession session) {
                    if (!resumed || closed || ar != configuredAr || active) return;
                    try {
                        configuredAr.resume(); active = true; opening = false;
                        configuredShared.setCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                                if(ar!=configuredAr || !active || request.getTag() instanceof Pending || request.getTag() instanceof FocusCommand) return;
                                // Observe ARCore-owned repeating capture; never replace its repeating request.
                                FocusPolicy.Sample sample=focusSample(request,result,SystemClock.elapsedRealtimeNanos());
                                if(releasingFocus || focusResetFailed || sample.timestampNs()<=focusResetAfterSensorNs) return;
                                lastResult = result;
                                focus.add(sample);
                                Pending waiting=pending;
                                if(waiting!=null && waiting.waitingForLock) captureIfLocked(waiting,sample);
                            }
                        }, handler);
                    } catch (Exception error) { fail("Cannot start tracking: " + error.getMessage()); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    session.close();
                    if (!resumed || closed || ar != configuredAr) return;
                    jpegAttempt++;
                    if (jpegAttempt < Math.min(jpegSizes.size(), 5)) configure();
                    else { cpuFallback = true; fail("High-resolution stream unavailable. Using labelled CPU fallback. Try a phone capture before judging fine detail."); configure(); }
                }
            }, handler), handler);
        } catch (Exception error) { fail("Camera configuration failed: " + error.getMessage()); }
    }

    void capture() {
        if (!active || !resumed || closed) { fail("Wait for the viewfinder to start."); return; }
        if(focusResetFailed) { fail("Autofocus reset failed. Return Home, resume this capture and retry."); return; }
        if(releasingFocus) { fail("Resetting autofocus. Hold still, then tap Capture again."); return; }
        if (!state.begin()) return;
        FrameEvidence current = evidence;
        Pending shot = new Pending(epoch, holdout ? "holdout" : "reconstruct", current,quality,cpuFallback,freezeColor,cameraId,characteristics);
        shot.requestedElapsedNs = SystemClock.elapsedRealtimeNanos();
        shot.lockSequence=new FocusLockSequence(shot.requestedElapsedNs);
        shot.originAr=ar; shot.originCaptures=captures; shot.originDevice=device; shot.originShared=shared;
        shot.focusAtTrigger = focus.readiness(shot.requestedElapsedNs);
        pending = shot;
        if (cpuFallback) { cpuCaptureRequested = true; }
        else { shot.waitingForFocus=true; handler.post(() -> awaitSettledFocus(shot)); }
        handler.postDelayed(() -> {
            if (pending != shot || shot.saving) return;
            releaseFocus(shot,"overall-timeout");
            if (shot.jpeg != null) {
                if(!shot.cancelFinished && shot.lockSequence.cancelRequested()) cancelFailed(shot,"Focus reset exceeded the capture deadline.");
                savePending(shot);
            } else abortShot(shot,"Photo timed out. No observation was saved. Keep the phone steady and retry.");
        }, 8000);
    }

    /** Metadata polling has a deadline; a fixed delay alone never establishes focus. */
    private void awaitSettledFocus(Pending shot) {
        if(pending!=shot || shot.saving) return;
        if(!active || !resumed || closed || !epoch.equals(shot.epoch)) {
            abortShot(shot,"Focus check interrupted. Hold still and retry this view."); return;
        }
        long now=SystemClock.elapsedRealtimeNanos();
        FocusPolicy.Readiness readiness=focus.readiness(now);
        FrameEvidence current=evidence;
        boolean stillTracking=current!=null && current.tracking().equals("TRACKING") && current.stable();
        if(readiness.ready() && stillTracking) {
            shot.focusBeforeCapture=readiness; shot.requestedAfMode=readiness.latest().mode(); shot.waitingForFocus=false;
            requestFocusLock(shot); return;
        }
        if(FocusPolicy.timedOut(shot.requestedElapsedNs,now)) {
            String reason=stillTracking?readiness.reason():"camera movement or limited tracking";
            abortShot(shot,"No photo saved: "+reason+". Hold still, step back slightly and tap Capture again."); return;
        }
        handler.postDelayed(() -> awaitSettledFocus(shot),60);
    }

    private void requestFocusLock(Pending shot) {
        long now=SystemClock.elapsedRealtimeNanos();
        if(!shot.lockSequence.requestStart(shot.requestedAfMode,now)) {
            abortShot(shot,"Focus lock deadline passed. Hold still and retry."); return;
        }
        shot.waitingForLock=true; shot.startRequestedElapsedNs=now;
        TotalCaptureResult repeating=lastResult;
        if(repeating!=null) {
            shot.requestedControlMode=repeating.get(CaptureResult.CONTROL_MODE);
            shot.repeatingCaptureIntent=repeating.get(CaptureResult.CONTROL_CAPTURE_INTENT);
        }
        try { sendFocusCommand(shot,false); }
        catch(Exception error) { abortShot(shot,"Cannot request focus lock: "+error.getMessage()); return; }
        awaitLockedFocus(shot);
    }

    private void awaitLockedFocus(Pending shot) {
        if(pending!=shot || !shot.waitingForLock || shot.saving) return;
        if(!sameCamera(shot) || !resumed || closed || shot.lockSequence.expired(SystemClock.elapsedRealtimeNanos())) {
            abortShot(shot,"Focus did not lock. Hold still, step back slightly and retry this view."); return;
        }
        captureIfLocked(shot,focus.readiness(SystemClock.elapsedRealtimeNanos()).latest());
        if(shot.waitingForLock) handler.postDelayed(() -> awaitLockedFocus(shot),60);
    }

    private void captureIfLocked(Pending shot,FocusPolicy.Sample sample) {
        if(pending!=shot || !shot.waitingForLock || !sameCamera(shot) || !resumed || closed) return;
        if(!shot.lockSequence.acceptLocked(sample,SystemClock.elapsedRealtimeNanos())) return;
        shot.lockedFocus=sample; shot.waitingForLock=false; captureJpeg(shot);
    }

    private boolean sameCamera(Pending shot) {
        return active && ar==shot.originAr && captures==shot.originCaptures && device==shot.originDevice;
    }

    /** START and CANCEL target only ARCore surfaces and have callbacks distinct from JPEG capture. */
    private void sendFocusCommand(Pending shot,boolean cancel) throws Exception {
        if(!sameCamera(shot) || !resumed || closed) throw new IllegalStateException("Originating camera session is closed");
        FocusCommand command=new FocusCommand(shot,cancel);
        CaptureRequest.Builder request=shot.originDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        for(Surface surface:shot.originShared.getArCoreSurfaces()) request.addTarget(surface);
        request.set(CaptureRequest.CONTROL_AF_MODE,shot.requestedAfMode);
        request.set(CaptureRequest.CONTROL_AF_TRIGGER,cancel?CaptureRequest.CONTROL_AF_TRIGGER_CANCEL:CaptureRequest.CONTROL_AF_TRIGGER_START);
        if(shot.requestedControlMode!=null) request.set(CaptureRequest.CONTROL_MODE,shot.requestedControlMode);
        if(shot.repeatingCaptureIntent!=null) request.set(CaptureRequest.CONTROL_CAPTURE_INTENT,shot.repeatingCaptureIntent);
        request.setTag(command);
        shot.originCaptures.capture(request.build(),new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest completed,TotalCaptureResult result) {
                if(completed.getTag()!=command || ar!=shot.originAr || session!=shot.originCaptures) return;
                FocusPolicy.Sample sample=focusSample(completed,result,SystemClock.elapsedRealtimeNanos());
                if(cancel) {
                    if(shot.cancelFinished) return;
                    shot.cancelFocusResult=sample; shot.cancelReportedTrigger=result.get(CaptureResult.CONTROL_AF_TRIGGER);
                    shot.cancelReportedControlMode=result.get(CaptureResult.CONTROL_MODE); shot.cancelReportedIntent=result.get(CaptureResult.CONTROL_CAPTURE_INTENT);
                    shot.cancelStatus="completed"; shot.cancelFinished=true;
                    focusResetAfterSensorNs=Math.max(focusResetAfterSensorNs,sample.timestampNs());
                    focus.clear(); lastResult=null; releasingFocus=false;
                } else {
                    if(pending!=shot || !shot.waitingForLock) return;
                    shot.startFocusResult=sample; shot.startReportedTrigger=result.get(CaptureResult.CONTROL_AF_TRIGGER);
                    shot.startReportedControlMode=result.get(CaptureResult.CONTROL_MODE); shot.startReportedIntent=result.get(CaptureResult.CONTROL_CAPTURE_INTENT);
                    shot.lockSequence.startCompleted(sample); captureIfLocked(shot,sample);
                }
            }
            @Override public void onCaptureFailed(CameraCaptureSession session,CaptureRequest failed,CaptureFailure failure) {
                if(failed.getTag()!=command || ar!=shot.originAr) return;
                if(cancel) cancelFailed(shot,"Autofocus reset request failed ("+failure.getReason()+").");
                else abortShot(shot,"Focus lock request failed. Hold still and retry this view.");
            }
        },handler);
    }

    private void releaseFocus(Pending shot,String reason) {
        if(shot.lockSequence==null || !shot.lockSequence.claimCancel()) return;
        shot.cancelReason=reason; shot.cancelStatus="requested"; shot.cancelRequestedElapsedNs=SystemClock.elapsedRealtimeNanos();
        // Block the next capture until CANCEL completes and fresh post-CANCEL results arrive.
        if(ar==shot.originAr) { releasingFocus=true; focus.clear(); lastResult=null; }
        try { sendFocusCommand(shot,true); }
        catch(Exception error) { cancelFailed(shot,"Autofocus reset unavailable: "+error.getMessage()); return; }
        handler.postDelayed(() -> {
            if(!shot.cancelFinished) cancelFailed(shot,"Autofocus reset timed out.");
        },1000);
    }

    private void cancelFailed(Pending shot,String message) {
        if(shot.cancelFinished) return;
        shot.cancelStatus="failed"; shot.cancelFinished=true;
        shot.focusWarning=(shot.focusWarning==null?"":shot.focusWarning+" ")+message;
        if(ar==shot.originAr) {
            focus.clear(); releasingFocus=false; focusResetFailed=true;
            fail(message+" Return Home, resume this capture and retry.");
        }
    }

    private void abortShot(Pending shot,String message) {
        releaseFocus(shot,"failure-or-timeout");
        if(pending==shot) { pending=null; state.finish(); fail(message); }
    }

    private void captureJpeg(Pending shot) {
        try {
            if (!sameCamera(shot) || jpegReader == null || shot.lockSequence.expired(SystemClock.elapsedRealtimeNanos())) throw new IllegalStateException("Camera unavailable or capture deadline passed");
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(jpegReader.getSurface());
            for (Surface surface : shared.getArCoreSurfaces()) request.addTarget(surface);
            request.set(CaptureRequest.JPEG_QUALITY, (byte)100);
            request.set(CaptureRequest.JPEG_ORIENTATION, 0);
            request.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
            request.set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            if(shot.requestedAfMode==null) throw new IllegalStateException("No settled autofocus mode");
            // An AF mode switch resets focus. Reuse the mode observed on the repeating stream.
            request.set(CaptureRequest.CONTROL_AF_MODE, shot.requestedAfMode);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            if(shot.requestedControlMode!=null) request.set(CaptureRequest.CONTROL_MODE,shot.requestedControlMode);
            request.setTag(shot);
            if (shot.freezeRequested) {
                if (fixedControls == null) fixedControls = lastResult;
                TotalCaptureResult baseline = fixedControls;
                if (baseline != null) {
                    RggbChannelVector gains = baseline.get(CaptureResult.COLOR_CORRECTION_GAINS);
                    ColorSpaceTransform transform = baseline.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                    if (gains != null && transform != null) {
                        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                        request.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                        request.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains);
                        request.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform);
                    } else if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)))
                        request.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                    if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))
                        request.set(CaptureRequest.CONTROL_AE_LOCK, true);
                }
            }
            captures.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (pending != shot || r.getTag()!=shot) return;
                    shot.result = result;
                    shot.focusWarning=FocusPolicy.stillWarning(shot.lockedFocus,
                            focusSample(r,result,SystemClock.elapsedRealtimeNanos()));
                    releaseFocus(shot,"still-completed");
                    attachJpeg(shot);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (pending == shot) abortShot(shot,"Still capture failed (" + failure.getReason() + "). Try again or use CPU fallback.");
                }
            }, handler);
        } catch (Exception error) {
            if (pending == shot) abortShot(shot,"Still capture failed: " + error.getMessage());
        }
    }

    private void jpegAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            Pending shot = pending;
            ByteBuffer bytes = image.getPlanes()[0].getBuffer(); byte[] jpeg = new byte[bytes.remaining()]; bytes.get(jpeg);
            receivedJpegs.put(image.getTimestamp(),new JpegFrame(jpeg,image.getWidth(),image.getHeight()));
            while(receivedJpegs.size()>4) receivedJpegs.remove(receivedJpegs.keySet().iterator().next());
            if (shot != null && !shot.saving) attachJpeg(shot);
        } catch (Exception error) { fail("Cannot receive JPEG: " + error.getMessage()); }
    }

    /** Arrival order never assigns a JPEG to a request. Only matching sensor timestamps do. */
    private void attachJpeg(Pending shot) {
        if(pending!=shot || shot.saving || shot.result==null) return;
        Long timestamp=shot.result.get(CaptureResult.SENSOR_TIMESTAMP);
        if(timestamp==null) return;
        JpegFrame image=receivedJpegs.remove(timestamp);
        if(image==null) return;
        shot.jpeg=image.bytes(); shot.width=image.width(); shot.height=image.height(); shot.imageTimestamp=timestamp;
        handler.postDelayed(() -> savePending(shot),100);
    }

    private void savePending(Pending shot) {
        if (pending != shot || shot.saving || shot.jpeg == null) return;
        if(shot.lockSequence.cancelRequested() && !shot.cancelFinished) {
            if(shot.lockSequence.expired(SystemClock.elapsedRealtimeNanos())) cancelFailed(shot,"Focus reset exceeded the capture deadline.");
            else { handler.postDelayed(() -> savePending(shot),30); return; }
        }
        shot.saving = true;
        CaptureQuality captureQuality = shot.previewQuality;
        PoseHistory.Match pose = poses.match(shot.imageTimestamp, shot.epoch);
        writer.execute(() -> {
            try {
                JSONObject record = observation(shot, pose, captureQuality);
                String id = store.commit(shot.jpeg, record);
                activity.runOnUiThread(() -> {
                    listener.saved(id);
                    if(shot.focusWarning!=null) listener.error("Photo "+id+" saved with a focus warning. "+shot.focusWarning);
                    else listener.error("Photo saved. Check fine detail in Review before continuing.");
                });
            } catch (Exception error) { fail("Photo was not saved: " + error.getMessage()); }
            finally { if (pending == shot) pending = null; state.finish(); }
        });
    }

    private JSONObject observation(Pending shot, PoseHistory.Match match, CaptureQuality captureQuality) throws Exception {
        JSONObject record = new JSONObject().put("epoch", shot.epoch).put("role", shot.role)
                .put("timestampNs", Long.toString(shot.imageTimestamp)).put("width", shot.width).put("height", shot.height)
                .put("cameraId", shot.cameraId).put("poseStatus", match.status())
                .put("poseDeltaNs", match.deltaNs() == null ? JSONObject.NULL : Long.toString(match.deltaNs()))
                .put("cameraToWorld", match.sample() == null ? JSONObject.NULL : array(match.sample().matrix()))
                .put("intrinsics", JSONObject.NULL).put("calibrationStatus", "unavailable")
                .put("source", shot.cpuSource ? "arcore-cpu-yuv-jpeg-fallback" : "camera2-jpeg-original")
                .put("requestedControls", new JSONObject().put("freezeStillColour", shot.freezeRequested).put("jpegQuality", shot.cpuSource ? 98 : 100)
                        .put("afMode",shot.requestedAfMode==null?JSONObject.NULL:shot.requestedAfMode)
                        .put("controlMode",shot.requestedControlMode==null?JSONObject.NULL:shot.requestedControlMode)
                        .put("captureIntent",shot.cpuSource?JSONObject.NULL:CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        .put("afTrigger",shot.cpuSource?JSONObject.NULL:CaptureRequest.CONTROL_AF_TRIGGER_IDLE));
        if (match.sample() != null) {
            PoseHistory.Sample sample = match.sample();
            JSONObject cpu = intrinsics(sample.cpuIntrinsics()).put("width", sample.width()).put("height", sample.height());
            record.put("cpuIntrinsics", cpu);
            if (shot.cpuSource && match.status().equals("matched") && sample.width() == shot.width && sample.height() == shot.height)
                record.put("intrinsics", intrinsics(sample.cpuIntrinsics())).put("calibrationStatus", "calibrated");
        }
        JSONObject q = new JSONObject().put("trackingAtTrigger", shot.trigger == null ? "unavailable" : shot.trigger.tracking())
                .put("stableAtTrigger", shot.trigger != null && shot.trigger.stable())
                .put("captureRequestedElapsedRealtimeNs",Long.toString(shot.requestedElapsedNs))
                .put("repeatingFocusAtTrigger",focusEvidence(shot.focusAtTrigger))
                .put("repeatingFocusBeforeStill",focusEvidence(shot.focusBeforeCapture))
                .put("focusLock",lockEvidence(shot))
                .put("sharpnessScope", "CPU preview centre 70%; not a full-resolution still sharpness certification");
        if(shot.cpuSource) shot.focusWarning="CPU fallback has no validated still focus result. Inspect the full-size image before use.";
        q.put("focusStatus",shot.focusWarning==null?"locked-metadata":"warning");
        if(shot.focusWarning!=null) q.put("focusWarning",shot.focusWarning);
        if (captureQuality != null) q.put("previewLaplacianVariance", captureQuality.sharpness())
                .put("previewDarkFraction", captureQuality.darkFraction()).put("previewBrightFraction", captureQuality.brightFraction());
        record.put("quality", q);
        JSONObject controls = new JSONObject(); JSONObject sensor = new JSONObject();
        if (shot.characteristics != null) {
            Rect activeArray = shot.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeArray != null) sensor.put("activeArray", rect(activeArray));
            put(sensor, "sensorOrientation", shot.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
            put(sensor, "timestampSource", shot.characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
        }
        TotalCaptureResult result = shot.result;
        Long resultTime = result == null ? null : result.get(CaptureResult.SENSOR_TIMESTAMP);
        boolean paired = resultTime != null && resultTime == shot.imageTimestamp;
        controls.put("metadataStatus", paired ? "timestamp-matched" : "unavailable");
        if (paired) {
            put(controls, "exposureTimeNs", asString(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)));
            put(controls, "iso", result.get(CaptureResult.SENSOR_SENSITIVITY));
            put(controls, "focusDistanceDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE));
            put(controls, "awbState", result.get(CaptureResult.CONTROL_AWB_STATE));
            put(controls, "awbMode", result.get(CaptureResult.CONTROL_AWB_MODE));
            put(controls, "awbLock", result.get(CaptureResult.CONTROL_AWB_LOCK));
            put(controls, "aeState", result.get(CaptureResult.CONTROL_AE_STATE));
            put(controls, "aeLock", result.get(CaptureResult.CONTROL_AE_LOCK));
            put(controls, "afState", result.get(CaptureResult.CONTROL_AF_STATE));
            put(controls, "afMode", result.get(CaptureResult.CONTROL_AF_MODE));
            put(controls, "afTrigger", result.get(CaptureResult.CONTROL_AF_TRIGGER));
            put(controls, "controlMode", result.get(CaptureResult.CONTROL_MODE));
            put(controls, "captureIntent", result.get(CaptureResult.CONTROL_CAPTURE_INTENT));
            put(controls, "lensState", result.get(CaptureResult.LENS_STATE));
            put(controls, "sensorTimestampNs", asString(resultTime));
            RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (gains != null) controls.put("whiteBalanceGains", array(new float[]{gains.getRed(), gains.getGreenEven(), gains.getGreenOdd(), gains.getBlue()}));
            ColorSpaceTransform colorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if(colorTransform != null) { JSONArray values = new JSONArray(); for(int row=0;row<3;row++) for(int column=0;column<3;column++) values.put(colorTransform.getElement(column,row).toString()); controls.put("colourTransformRowMajor",values); }
            put(controls,"opticalStabilizationMode",result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE));
            put(controls,"videoStabilizationMode",result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE));
            put(controls,"distortionCorrectionMode",result.get(CaptureResult.DISTORTION_CORRECTION_MODE));
            Rect crop = result.get(CaptureResult.SCALER_CROP_REGION); if (crop != null) sensor.put("cropRegion", rect(crop));
            put(sensor, "physicalCameraId", result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID));
            float[] calibration = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
            if (calibration != null) sensor.put("sensorIntrinsics", array(calibration));
            float[] distortion = result.get(CaptureResult.LENS_DISTORTION);
            if (distortion != null) sensor.put("distortion", array(distortion));
            put(sensor, "focalLengthMm", result.get(CaptureResult.LENS_FOCAL_LENGTH));
        }
        record.put("sensor", sensor).put("controls", controls);
        return record;
    }

    private static Object asString(Object value) { return value == null ? null : value.toString(); }
    private static FocusPolicy.Sample focusSample(CaptureRequest request,TotalCaptureResult result,long receivedNs) {
        Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
        return new FocusPolicy.Sample(timestamp==null?0:timestamp,receivedNs,result.get(CaptureResult.CONTROL_AF_MODE),
                result.get(CaptureResult.CONTROL_AF_STATE),result.get(CaptureResult.LENS_STATE),result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                request.get(CaptureRequest.CONTROL_AF_MODE));
    }
    private static Object focusEvidence(FocusPolicy.Readiness readiness) throws Exception {
        if(readiness==null) return JSONObject.NULL;
        JSONObject value=new JSONObject().put("ready",readiness.ready()).put("reason",readiness.reason())
                .put("stableDurationNs",Long.toString(readiness.stableDurationNs())).put("samples",readiness.samples());
        FocusPolicy.Sample sample=readiness.latest();
        if(sample!=null) {
            value.put("sensorTimestampNs",Long.toString(sample.timestampNs())).put("receivedElapsedRealtimeNs",Long.toString(sample.receivedElapsedNs()));
            put(value,"afMode",sample.mode()); put(value,"requestedAfMode",sample.requestedMode());
            put(value,"afState",sample.state()); put(value,"lensState",sample.lensState()); put(value,"focusDistanceDiopters",sample.diopters());
        }
        return value;
    }
    private static Object focusSampleEvidence(FocusPolicy.Sample sample) throws Exception {
        if(sample==null) return JSONObject.NULL;
        JSONObject value=new JSONObject().put("sensorTimestampNs",Long.toString(sample.timestampNs()))
                .put("receivedElapsedRealtimeNs",Long.toString(sample.receivedElapsedNs()));
        put(value,"afMode",sample.mode()); put(value,"requestedAfMode",sample.requestedMode());
        put(value,"afState",sample.state()); put(value,"lensState",sample.lensState()); put(value,"focusDistanceDiopters",sample.diopters());
        return value;
    }
    private static JSONObject lockEvidence(Pending shot) throws Exception {
        JSONObject value=new JSONObject().put("phase",shot.lockSequence.phase().name()).put("overallDeadlineNs",Long.toString(FocusLockSequence.OVERALL_TIMEOUT_NS))
                .put("startTrigger",CaptureRequest.CONTROL_AF_TRIGGER_START).put("startResult",focusSampleEvidence(shot.startFocusResult))
                .put("lockedResult",focusSampleEvidence(shot.lockedFocus)).put("cancelTrigger",CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                .put("cancelStatus",shot.cancelStatus).put("cancelResult",focusSampleEvidence(shot.cancelFocusResult));
        put(value,"startRequestedElapsedRealtimeNs",asString(shot.startRequestedElapsedNs));
        put(value,"startReportedTrigger",shot.startReportedTrigger); put(value,"startReportedControlMode",shot.startReportedControlMode); put(value,"startReportedCaptureIntent",shot.startReportedIntent);
        put(value,"cancelRequestedElapsedRealtimeNs",asString(shot.cancelRequestedElapsedNs)); put(value,"cancelReason",shot.cancelReason);
        put(value,"cancelReportedTrigger",shot.cancelReportedTrigger); put(value,"cancelReportedControlMode",shot.cancelReportedControlMode); put(value,"cancelReportedCaptureIntent",shot.cancelReportedIntent);
        put(value,"requestedControlMode",shot.requestedControlMode); put(value,"requestedCommandCaptureIntent",shot.repeatingCaptureIntent);
        return value;
    }
    private static void put(JSONObject o, String key, Object value) throws Exception { if (value != null) o.put(key, value); }
    private static JSONArray rect(Rect r) throws Exception { return new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}); }
    private static JSONArray array(float[] values) throws Exception { JSONArray a = new JSONArray(); for (float value : values) a.put((double)value); return a; }
    private static JSONObject intrinsics(float[] values) throws Exception {
        return new JSONObject().put("fx", values[0]).put("fy", values[1]).put("cx", values[2]).put("cy", values[3]);
    }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) { background.create(); open(); }
    @Override public void onSurfaceChanged(GL10 unused, int width, int height) { this.width = width; this.height = height; GLES20.glViewport(0,0,width,height); }
    @Override public void onDrawFrame(GL10 unused) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        synchronized (lifecycle) {
            Session session = ar;
            if (session == null || !active || !resumed || closed) return;
            try {
                session.setCameraTextureName(background.texture);
                session.setDisplayGeometry(activity.getWindowManager().getDefaultDisplay().getRotation(), width, height);
                Frame frame = session.update(); background.draw(frame);
                long time = frame.getTimestamp(); if (time == 0 || time == previousFrameTime) return;
                com.google.ar.core.Camera camera = frame.getCamera();
                float[] matrix = new float[16]; camera.getPose().toMatrix(matrix, 0);
                CameraIntrinsics k = camera.getImageIntrinsics(); float[] focal = k.getFocalLength(), center = k.getPrincipalPoint();
                int[] dimensions = k.getImageDimensions();
                boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
                boolean stable = previousPose != null && motionSmall(previousPose, matrix) && tracking;
                stableFrames = stable ? stableFrames + 1 : 0;
                previousPose = matrix.clone(); previousFrameTime = time;
                FrameEvidence now = new FrameEvidence(time, epoch, matrix, new float[]{focal[0],focal[1],center[0],center[1]}, dimensions[0], dimensions[1], camera.getTrackingState().name(), stableFrames >= 8);
                evidence = now;
                poses.add(new PoseHistory.Sample(time, matrix, now.intrinsics(), dimensions[0], dimensions[1], epoch, tracking));
                if (time - qualityTimeNs > 200_000_000L || cpuCaptureRequested) {
                    try (Image image = frame.acquireCameraImage()) {
                        Image.Plane plane = image.getPlanes()[0]; ByteBuffer y = plane.getBuffer().duplicate();
                        byte[] luma = new byte[y.remaining()]; y.get(luma);
                        quality = CaptureQuality.analyze(luma, image.getWidth(), image.getHeight(), plane.getRowStride(), plane.getPixelStride()); qualityTimeNs = time;
                        if (cpuCaptureRequested) {
                            cpuCaptureRequested = false;
                            Pending shot = pending;
                            if (shot != null && !shot.saving) {
                                shot.jpeg = jpeg(image); shot.imageTimestamp = image.getTimestamp(); shot.width = image.getWidth(); shot.height = image.getHeight();
                                handler.post(() -> savePending(shot));
                            }
                        }
                    } catch (com.google.ar.core.exceptions.NotYetAvailableException ignored) { }
                }
                if (state.autoReady(time, tracking, now.stable(), quality, autoCapture) && !holdout) activity.runOnUiThread(this::capture);
                long ms = System.currentTimeMillis();
                if (ms - lastStatusMs > 350) {
                    lastStatusMs = ms;
                    String resolution = cpuFallback ? dimensions[0] + " × " + dimensions[1] + " CPU FALLBACK" : selectedJpeg == null ? "configuring JPEG" : selectedJpeg.getWidth() + " × " + selectedJpeg.getHeight() + " JPEG";
                    String sharp = quality == null ? "unavailable" : String.format(Locale.US,"%.0f",quality.sharpness());
                    FocusPolicy.Readiness focusStatus=focus.readiness(SystemClock.elapsedRealtimeNanos());
                    Pending waiting=pending;
                    String focusText=releasingFocus?"Resetting autofocus…":waiting!=null && waiting.waitingForLock?"Locking focus… hold still":waiting!=null && waiting.waitingForFocus?"Waiting for focus… hold still":focusStatus.reason();
                    String message = resolution + "  •  " + now.tracking() + "\nPreview sharpness " + sharp + "  •  " + (now.stable() ? "steady" : "hold still")
                            + "\nFocus: "+focusText
                            + "\n" + store.count() + " photos  •  " + (freezeColor ? "still colour freeze requested; actual state saved" : "automatic colour; actual state saved");
                    activity.runOnUiThread(() -> listener.status(message, true));
                }
            } catch (Exception error) {
                long ms = System.currentTimeMillis();
                if (ms - lastStatusMs > 2000) { lastStatusMs = ms; fail("Viewfinder: " + error.getMessage()); }
            }
        }
    }

    private static boolean motionSmall(float[] a, float[] b) {
        double dx = a[12]-b[12], dy = a[13]-b[13], dz = a[14]-b[14];
        double forwardDot = a[8]*b[8] + a[9]*b[9] + a[10]*b[10];
        return dx*dx+dy*dy+dz*dz < .000004 && forwardDot > .9998;
    }

    private static byte[] jpeg(Image image) throws Exception {
        int width = image.getWidth(), height = image.getHeight(); byte[] nv21 = new byte[width*height*3/2];
        Image.Plane[] planes = image.getPlanes();
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++)
            nv21[row*width+col] = planes[0].getBuffer().get(row*planes[0].getRowStride()+col*planes[0].getPixelStride());
        int start = width*height;
        for (int row = 0; row < height/2; row++) for (int col = 0; col < width/2; col++) {
            nv21[start++] = planes[2].getBuffer().get(row*planes[2].getRowStride()+col*planes[2].getPixelStride());
            nv21[start++] = planes[1].getBuffer().get(row*planes[1].getRowStride()+col*planes[1].getPixelStride());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0,0,width,height), 98, out)) throw new IllegalStateException("YUV conversion failed");
        return out.toByteArray();
    }

    private void fail(String message) { activity.runOnUiThread(() -> listener.error(message)); }
}
