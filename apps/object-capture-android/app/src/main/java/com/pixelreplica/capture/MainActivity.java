package com.pixelreplica.capture;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline capture UI. Photos never leave the app unless the user chooses save or share. */
public final class MainActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ScanStore store;
    private ScanCamera scanner;
    private boolean resumed;
    private LinearLayout root;
    private TextView cameraStatus, warning;
    private File exportFile;
    private boolean reviewing;
    private static final int BLUE = Color.rgb(80,175,255);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(15,23,36)); getWindow().setNavigationBarColor(Color.rgb(15,23,36));
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
        home();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (scanner != null) scanner.resume(); }
    @Override protected void onPause() { resumed = false; if (scanner != null) scanner.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (scanner != null) scanner.close(); io.shutdown(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (scanner != null && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) scanner.resume();
        else toast("Camera permission is needed. Enable it in Android app settings to capture.");
    }

    private void base(String title) {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15,23,36)); root.setPadding(dp(16),dp(12),dp(16),dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom);
            } else v.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(16)+insets.getSystemWindowInsetRight(),dp(12)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        TextView heading = text(title, 25); heading.setTextColor(BLUE); root.addView(heading);
    }
    private void home() {
        stopScanner(); reviewing = false; base("Pixel Replica");
        root.addView(text("Capture an object. Preserve the evidence.", 16));
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        body.addView(text("First scan: LEGO car\n\nKeep the car still and move the phone around it. Use bright, diffuse light. Fill the centre guide with the car, with some background around it. Make overlapping low, middle and high passes. Avoid flash and digital zoom.",16));
        body.addView(text("Include a ruler and a neutral colour card in setup photos. Measure the car independently. Glossy, black and transparent parts can still be difficult to reconstruct.",15));
        EditText name = input("Object name", "LEGO car", false); body.addView(name);
        CheckBox fallback = check("Use lower-resolution CPU fallback (diagnostic)"); body.addView(fallback);
        button(body, "Start new capture", () -> {
            try {
                JSONObject device = new JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
                        .put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("appVersion","0.1.0");
                store = ScanStore.create(new File(getFilesDir(),"sessions"),name.getText().toString(),device);
                capture(fallback.isChecked());
            } catch (Exception e) { error("Cannot create session",e); }
        });
        body.addView(text("Saved sessions",21));
        File[] sessions = new File(getFilesDir(),"sessions").listFiles(File::isDirectory);
        if (sessions == null || sessions.length == 0) body.addView(text("Your captures stay on this phone until you save or share a ZIP.",15));
        else {
            Arrays.sort(sessions, Comparator.comparingLong(File::lastModified).reversed());
            for (File session : sessions) {
                try {
                    ScanStore existing = ScanStore.open(session);
                    button(body,existing.name() + " • " + existing.count() + " photos • " + existing.status(),() -> { store = existing; review(); });
                } catch (Exception ignored) { body.addView(text("A session needs recovery: " + session.getName(),13)); }
            }
        }
        body.addView(text("No account. No cloud upload. Fine-detail, colour and size accuracy are not yet verified on your phone.",14));
    }

    private void capture(boolean fallback) {
        stopScanner(); reviewing = false; base(store.name());
        cameraStatus = text("Starting camera…",14); root.addView(cameraStatus);
        FrameLayout viewfinder = new FrameLayout(this);
        root.addView(viewfinder,new LinearLayout.LayoutParams(-1,0,1));
        scanner = new ScanCamera(this,store,new ScanCamera.Listener() {
            @Override public void status(String status, boolean ready) { if (cameraStatus != null && !reviewing) cameraStatus.setText(status); }
            @Override public void saved(String id) { if (!reviewing) toast("Photo " + id + " saved"); }
            @Override public void error(String status) { if (warning != null && !reviewing) warning.setText(status); }
        },fallback);
        viewfinder.addView(scanner.view,new FrameLayout.LayoutParams(-1,-1));
        View guide = new View(this); android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(Color.TRANSPARENT); outline.setStroke(dp(2),Color.argb(180,80,175,255)); outline.setCornerRadius(dp(12)); guide.setBackground(outline);
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(-1,-1); guideParams.setMargins(dp(22),dp(24),dp(22),dp(24)); viewfinder.addView(guide,guideParams);
        warning = text("Keep the object still. The outline is a framing guide, not a surface-coverage measurement.",13); root.addView(warning);
        LinearLayout switches = row();
        CheckBox automatic = check("Auto when steady"); automatic.setTextSize(13); switches.addView(automatic,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox holdout = check("Holdout photo"); holdout.setTextSize(13); switches.addView(holdout,new LinearLayout.LayoutParams(0,-2,1)); root.addView(switches);
        automatic.setOnCheckedChangeListener((button, checked) -> { if (scanner != null) scanner.setAutoCapture(checked); });
        holdout.setOnCheckedChangeListener((button, checked) -> { if (scanner != null) scanner.setHoldout(checked); if (checked) warning.setText("Holdouts are reserved for comparison and excluded from reconstruction. Capture a few useful viewpoints."); });
        CheckBox freeze = check("Request fixed still white balance (after warm-up)"); freeze.setTextSize(13); root.addView(freeze);
        freeze.setOnCheckedChangeListener((button, checked) -> { if (scanner != null) scanner.setFreezeColor(checked); });
        LinearLayout actions = row(); root.addView(actions);
        button(actions,"Capture",() -> { if (scanner != null) scanner.capture(); });
        button(actions,"New pass",() -> new AlertDialog.Builder(this).setTitle("Start a separate pass?")
                .setMessage("Use a new pass after moving or turning over the car. Previous photos remain. The laptop must align separate passes; their camera coordinates are not the same world.")
                .setPositiveButton("New pass",(d,w) -> { if (scanner != null) scanner.newEpoch(); }).setNegativeButton("Cancel",null).show());
        button(actions,"Review",() -> {
            if (scanner != null && scanner.isPending()) { toast("Wait for the photo to finish saving."); return; }
            try { store.complete(); review(); } catch (Exception e) { error("Cannot finish",e); }
        });
        if (resumed) scanner.resume();
    }

    private void review() {
        stopScanner(); reviewing = true; base(store.name());
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try {
            JSONArray photos = store.photos(); int holdouts = 0;
            Set<String> epochs = new HashSet<>();
            for (int i=0;i<photos.length();i++) { JSONObject photo = photos.getJSONObject(i); epochs.add(photo.getString("epoch")); if (photo.getString("role").equals("holdout")) holdouts++; }
            body.addView(text(photos.length() + " photos • " + holdouts + " holdouts\n" + epochs.size() + " coordinate passes • " + store.measurementCount() + " measurements",18));
            body.addView(text("Review for blur, gaps, reflections and missing underside views. Photo count does not establish complete geometry or accurate colour.",15));
            HorizontalScrollView thumbnails = new HorizontalScrollView(this); LinearLayout strip = row(); thumbnails.addView(strip); body.addView(thumbnails);
            for (int i=Math.max(0,photos.length()-12);i<photos.length();i++) {
                JSONObject photo = photos.getJSONObject(i); File file = new File(store.directory,photo.getString("file"));
                LinearLayout tile = column(); tile.setPadding(dp(3),0,dp(3),0);
                ImageView image = new ImageView(this); BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize=12;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(),options); image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(image,new LinearLayout.LayoutParams(dp(116),dp(88))); tile.addView(text(photo.getString("id") + (photo.getString("role").equals("holdout") ? " • check" : ""),12)); strip.addView(tile);
                image.setOnClickListener(v -> showPhoto(file,photo));
            }
            JSONArray measures = store.measurements();
            for (int i=0;i<measures.length();i++) { JSONObject m = measures.getJSONObject(i); body.addView(text(m.getString("label") + ": " + m.getDouble("valueMm") + " ± " + m.getDouble("uncertaintyMm") + " mm (" + m.getString("role") + ")",15)); }
        } catch (Exception e) { error("Cannot read session",e); }
        button(body,"Add physical measurement",this::measurementDialog);
        button(body,"Capture more photos",() -> capture(false));
        button(body,"Save capture ZIP…",() -> export(false));
        button(body,"Share capture ZIP…",() -> export(true));
        body.addView(text("Save the ZIP to Downloads, then connect the phone to your laptop by USB and choose File transfer. Copy the complete ZIP. Keep it intact for import.\n\nThe laptop reconstructs geometry and textures from the photos. The LLM uses tools and a review packet; it does not replace the measurements or invent missing surfaces.",15));
        button(body,"Home",this::home);
    }

    private void showPhoto(File file,JSONObject photo) {
        ImageView image = new ImageView(this); BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize=2;
        image.setImageBitmap(BitmapFactory.decodeFile(file.getPath(),options)); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        new AlertDialog.Builder(this).setTitle(photo.optString("id") + " • " + photo.optInt("width") + " × " + photo.optInt("height"))
                .setView(image).setMessage("Pose: " + photo.optString("poseStatus") + ". Calibration: " + photo.optString("calibrationStatus") + ". Original sensor orientation.")
                .setPositiveButton("Close",null).show();
    }

    private void measurementDialog() {
        LinearLayout form = column(); form.setPadding(dp(20),0,dp(20),0);
        EditText label=input("What did you measure?","Overall length",false), value=input("Value in mm","",true), uncertainty=input("Estimated uncertainty in mm","0.5",true);
        form.addView(label); form.addView(value); form.addView(uncertainty);
        Spinner role = new Spinner(this); role.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"scale","check"})); form.addView(role);
        form.addView(text("Use one scale measurement and separate check measurements. These are physical observations; the laptop still needs matching model points.",14));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Physical measurement").setView(form).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { store.measure(label.getText().toString(),Double.parseDouble(value.getText().toString()),Double.parseDouble(uncertainty.getText().toString()),role.getSelectedItem().toString()); dialog.dismiss(); review(); }
            catch (Exception e) { toast("Enter a name, a positive value and uncertainty of zero or more."); }
        })); dialog.show();
    }

    private void export(boolean share) {
        toast("Preparing capture ZIP…");
        io.execute(() -> {
            try {
                File archive=store.export(new File(getFilesDir(),"exports"));
                runOnUiThread(() -> {
                    exportFile=archive;
                    if (share) {
                        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",archive);
                        Intent intent=new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setClipData(ClipData.newRawUri("Object capture",uri));
                        startActivity(Intent.createChooser(intent,"Transfer capture ZIP"));
                    } else {
                        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                                .putExtra(Intent.EXTRA_TITLE,archive.getName()); startActivityForResult(intent,31);
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> error("Export failed",e)); }
        });
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != 31 || resultCode != RESULT_OK || data == null || data.getData() == null || exportFile == null) return;
        Uri destination=data.getData(); File source=exportFile;
        io.execute(() -> {
            try (OutputStream out=getContentResolver().openOutputStream(destination,"w"); InputStream in=new FileInputStream(source)) {
                if (out == null) throw new IOException("No destination stream");
                byte[] bytes=new byte[65536]; int count; while((count=in.read(bytes))!=-1) out.write(bytes,0,count);
                runOnUiThread(() -> toast("ZIP saved. Copy it to the laptop and import it."));
            } catch (Exception e) { runOnUiThread(() -> error("Could not save ZIP",e)); }
        });
    }

    private void stopScanner() { if (scanner != null) { scanner.close(); scanner=null; } }
    private void handleBack() {
        if (scanner != null && scanner.isPending()) { toast("Wait for the current photo to save."); return; }
        if (scanner != null) { review(); return; }
        if (reviewing) { home(); return; } finish();
    }
    private TextView text(String content,int size) { TextView text=new TextView(this); text.setText(content); text.setTextSize(size); text.setTextColor(Color.rgb(224,233,246)); text.setPadding(0,dp(6),0,dp(6)); return text; }
    private EditText input(String hint,String initial,boolean number) { EditText input=new EditText(this); input.setTextColor(Color.WHITE); input.setHintTextColor(Color.LTGRAY); input.setHint(hint); input.setText(initial); input.setSingleLine(true); if(number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return input; }
    private CheckBox check(String title) { CheckBox check=new CheckBox(this); check.setText(title); check.setTextColor(Color.WHITE); return check; }
    private LinearLayout column() { LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private void button(LinearLayout parent,String title,Runnable action) { Button button=new Button(this); button.setText(title); button.setTextSize(13); button.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams params = parent.getOrientation()==LinearLayout.HORIZONTAL ? new LinearLayout.LayoutParams(0,dp(52),1) : new LinearLayout.LayoutParams(-1,-2); parent.addView(button,params); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private void error(String title,Exception error) { new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()).setPositiveButton("Close",null).show(); }
}
