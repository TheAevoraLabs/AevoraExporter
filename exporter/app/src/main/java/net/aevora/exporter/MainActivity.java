package net.aevora.exporter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * Aevora Exporter v1 UI, deliberately plain: pick a mod zip, preview,
 * export, install. All heavy work runs on a background thread; the UI
 * thread only updates status text.
 */
public class MainActivity extends Activity {

    private static final int PICK_ZIP = 1;
    private static final Pattern PACKAGE_RE =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");

    private TextView status;
    private TextView preview;
    private Button exportBtn;
    private ModZip picked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Aevora Exporter");
        title.setTextSize(22);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Pick a Kiwi Launcher zip mod. The exporter patches the "
                + "bundled template APK with that mod's package, name, "
                + "version and icon, then hands the result to the installer.");
        root.addView(hint);

        Button pickBtn = new Button(this);
        pickBtn.setText("Pick mod zip");
        pickBtn.setOnClickListener(v -> pickZip());
        root.addView(pickBtn);

        preview = new TextView(this);
        preview.setText("No mod picked yet.");
        root.addView(preview);

        exportBtn = new Button(this);
        exportBtn.setText("Export APK");
        exportBtn.setEnabled(false);
        exportBtn.setOnClickListener(v -> exportApk());
        root.addView(exportBtn);

        status = new TextView(this);
        root.addView(status);

        setContentView(root);
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            try {
                byte[] bytes = readUri(uri);
                ModZip mod = ModZip.parse(bytes);
                picked = mod;
                runOnUiThread(() -> {
                    preview.setText("Mod: " + mod.name + "\nPackage: " + mod.id
                            + "\nVersion: " + mod.version
                            + "\nIcon: " + (mod.iconPng != null ? "yes" : "no"));
                    if (!PACKAGE_RE.matcher(mod.id).matches()) {
                        status("Warning: package '" + mod.id + "' is not a valid "
                                + "Android package. Export is blocked — fix the "
                                + "mod id in properties.toml, do not ship a "
                                + "broken APK.");
                        exportBtn.setEnabled(false);
                    } else {
                        status("Ready to export.");
                        exportBtn.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Could not read mod zip: " + e.getMessage()));
            }
        }).start();
    }

    private void exportApk() {
        if (picked == null) return;
        exportBtn.setEnabled(false);
        status("Exporting… patching template, signing, please wait.");
        final ModZip mod = picked;
        new Thread(() -> {
            try {
                File cacheDir = getCacheDir();
                File templateFile = new File(cacheDir, "template.apk");
                copyAsset("template.apk", templateFile);
                File unsigned = new File(cacheDir, "exported-unsigned.apk");
                ApkPatcher.patch(templateFile, mod, unsigned);
                File exportedDir = new File(getExternalFilesDir(null), "exported");
                exportedDir.mkdirs();
                File out = new File(exportedDir, mod.id + ".apk");
                Signer.sign(unsigned, out);
                runOnUiThread(() -> {
                    status("Exported: " + out.getName() + ". Opening installer…");
                    install(out);
                    exportBtn.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status("Export failed: " + e.getMessage());
                    exportBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void install(File apk) {
        Uri uri = Uri.parse("content://" + getPackageName()
                + ".fileprovider/exported/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void status(String s) {
        status.setText(s);
    }

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open zip");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
