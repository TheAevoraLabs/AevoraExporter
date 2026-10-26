package in.aevora.exporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Aevora Exporter v9 UI — Material-style, framework-only (no androidx,
 * so the sandbox build stays dependency-light apart from apksig).
 *
 * Layout, top to bottom:
 * - Top bar (purple): menu button -> side panel, title, "v9" chip.
 * - Import card: Pick mod zip + Shaders (experimental).
 * - Mod card (after a zip is picked): mod icon, name, result version
 *   (<mod>_kiwi9), package, description.
 * - Action row (after a zip is picked, and after export): Export,
 *   then Install / Share / Update export.
 * - General card: History, About, Check for updates.
 * - Side panel: Open RubyTouch, Open Launcher (original Kiwi
 *   Launcher), History, About, Check for updates, ReCaver
 *   (experimental), credits to Kizi and Quantum Creeper.
 *
 * Version rule: this app is v9 because the template in its assets is
 * Kiwi Launcher 9.0. Exported APKs do NOT carry v9 — they carry
 * <mod version>_kiwi9 (see ApkPatcher.exportedVersion).
 */
public class MainActivity extends Activity {

    private static final int PICK_ZIP = 1;
    private static final int PICK_APK = 2;
    private static final Pattern PACKAGE_RE =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/TheAevoraLabs/AevoraExporter"
            + "/main/_database/version.txt";
    /** Local major version, from _database/version.txt ("9"). */
    private static final int LOCAL_MAJOR = 9;

    private static final int PURPLE = 0xFF6750A4;
    private static final int SURFACE = 0xFFF6F2FA;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ON_SURFACE = 0xFF1D1B20;
    private static final int SECONDARY = 0xFF49454F;

    private TextView status;
    private LinearLayout modCard, actionRow;
    private ImageView modIcon;
    private TextView modName, modMeta;
    private Button exportBtn, installBtn, shareBtn, updateBtn;
    private ModZip picked;
    private File lastExported;
    private LinearLayout sidePanel;
    private View scrim;
    private boolean panelOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);

        // ---- Main scrollable content ----
        ScrollView scroll = new ScrollView(this);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PURPLE);
        top.setPadding(dp(12), dp(12), dp(12), dp(12));
        Button menu = new Button(this);
        menu.setText("\u2630");
        menu.setTextColor(0xFFFFFFFF);
        menu.setBackgroundColor(0x00000000);
        menu.setOnClickListener(v -> openPanel());
        top.addView(menu);
        TextView title = new TextView(this);
        title.setText("Aevora Exporter");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tp);
        top.addView(title);
        TextView chip = new TextView(this);
        chip.setText("v9");
        chip.setTextColor(0xFF1D1B20);
        chip.setBackgroundColor(0xFF7CE7C4);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        main.addView(top);

        // Import card
        LinearLayout importCard = card();
        TextView importTitle = new TextView(this);
        importTitle.setText("Import a mod");
        importTitle.setTextSize(18);
        importTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        importTitle.setTextColor(ON_SURFACE);
        importCard.addView(importTitle);
        TextView importHint = new TextView(this);
        importHint.setText("Pick a Kiwi Launcher zip mod. The exporter patches "
                + "the bundled template APK with that mod's package, name, "
                + "version and icon, then hands the result to the installer.");
        importHint.setTextColor(SECONDARY);
        importCard.addView(importHint);
        Button pickBtn = primaryButton("Pick mod zip");
        pickBtn.setOnClickListener(v -> pickZip());
        importCard.addView(pickBtn);
        Button shaders = secondaryButton("Shaders  •  experimental");
        shaders.setOnClickListener(v -> {
            Toast.makeText(this, "Shaders will be available soon!",
                    Toast.LENGTH_SHORT).show();
            status("Shaders: will be available soon!");
        });
        importCard.addView(shaders);
        main.addView(importCard);

        // Mod card (hidden until a zip is picked)
        modCard = card();
        modCard.setVisibility(View.GONE);
        LinearLayout modRow = new LinearLayout(this);
        modRow.setOrientation(LinearLayout.HORIZONTAL);
        modIcon = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
        ip.setMargins(0, 0, dp(12), 0);
        modIcon.setLayoutParams(ip);
        modRow.addView(modIcon);
        LinearLayout modText = new LinearLayout(this);
        modText.setOrientation(LinearLayout.VERTICAL);
        modName = new TextView(this);
        modName.setTextSize(19);
        modName.setTypeface(null, android.graphics.Typeface.BOLD);
        modName.setTextColor(ON_SURFACE);
        modText.addView(modName);
        modMeta = new TextView(this);
        modMeta.setTextColor(SECONDARY);
        modText.addView(modMeta);
        modRow.addView(modText);
        modCard.addView(modRow);
        main.addView(modCard);

        // Action row (hidden until a zip is picked)
        actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setVisibility(View.GONE);
        exportBtn = primaryButton("Export APK");
        exportBtn.setOnClickListener(v -> exportApk());
        installBtn = secondaryButton("Install");
        installBtn.setVisibility(View.GONE);
        installBtn.setOnClickListener(v -> {
            if (lastExported != null) install(lastExported);
        });
        shareBtn = secondaryButton("Share");
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(v -> {
            if (lastExported != null) share(lastExported);
        });
        updateBtn = secondaryButton("Update export");
        updateBtn.setVisibility(View.GONE);
        updateBtn.setOnClickListener(v -> exportApk());
        actionRow.addView(exportBtn);
        actionRow.addView(installBtn);
        actionRow.addView(shareBtn);
        actionRow.addView(updateBtn);
        main.addView(actionRow);

        // APK tools card: AEE-style align + resign for ANY APK.
        // Uses the copied AEE ZipAlign (GPLv3) + this app's own
        // AndroidKeyStore signer. This is the tool that fixes the
        // class of install failure the exporter hit on 2026-10-26:
        // unaligned .so files in an APK that the installer refuses.
        LinearLayout tools = card();
        TextView toolsTitle = new TextView(this);
        toolsTitle.setText("APK tools");
        toolsTitle.setTextSize(18);
        toolsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        toolsTitle.setTextColor(ON_SURFACE);
        tools.addView(toolsTitle);
        TextView toolsHint = new TextView(this);
        toolsHint.setText("Pick any APK: it gets zipaligned (4-byte, 16 KB "
                + "for .so files) and re-signed with this device's key, "
                + "then saved to History. Signatures are stripped first, "
                + "so an already-signed APK can be re-signed cleanly.");
        toolsHint.setTextColor(SECONDARY);
        tools.addView(toolsHint);
        Button alignBtn = secondaryButton("Pick APK to align & sign");
        alignBtn.setOnClickListener(v -> pickApk());
        tools.addView(alignBtn);
        main.addView(tools);

        // General card
        LinearLayout general = card();
        TextView generalTitle = new TextView(this);
        generalTitle.setText("General");
        generalTitle.setTextSize(18);
        generalTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        generalTitle.setTextColor(ON_SURFACE);
        general.addView(generalTitle);
        Button history = secondaryButton("History");
        history.setOnClickListener(v -> startActivity(
                new Intent(this, HistoryActivity.class)));
        general.addView(history);
        Button about = secondaryButton("About");
        about.setOnClickListener(v -> showAbout());
        general.addView(about);
        Button check = secondaryButton("Check for updates");
        check.setOnClickListener(v -> checkUpdate());
        general.addView(check);
        main.addView(general);

        status = new TextView(this);
        status.setTextColor(SECONDARY);
        status.setPadding(0, dp(8), 0, 0);
        main.addView(status);

        scroll.addView(main);
        root.addView(scroll);

        // ---- Side panel (scrim + sliding panel) ----
        scrim = new View(this);
        scrim.setBackgroundColor(0x80000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closePanel());
        root.addView(scrim);

        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setBackgroundColor(CARD);
        sidePanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        sidePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                dp(280), FrameLayout.LayoutParams.MATCH_PARENT);
        sp.gravity = Gravity.START;
        sidePanel.setLayoutParams(sp);
        sidePanel.setElevation(dp(8));

        TextView panelTitle = new TextView(this);
        panelTitle.setText("Aevora Exporter");
        panelTitle.setTextSize(20);
        panelTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        panelTitle.setTextColor(ON_SURFACE);
        sidePanel.addView(panelTitle);
        TextView panelSub = new TextView(this);
        panelSub.setText("v9  •  Template: Kiwi Launcher 9.0");
        panelSub.setTextColor(SECONDARY);
        sidePanel.addView(panelSub);

        Button openRuby = secondaryButton("Open RubyTouch");
        openRuby.setOnClickListener(v -> openApp("in.aevora.ruby", "RubyTouch"));
        sidePanel.addView(openRuby);
        Button openLauncher = secondaryButton("Open Launcher (Kiwi)");
        openLauncher.setOnClickListener(v -> openApp("net.kiwi.launcher",
                "Kiwi Launcher"));
        sidePanel.addView(openLauncher);
        Button panelHistory = secondaryButton("History");
        panelHistory.setOnClickListener(v -> {
            closePanel();
            startActivity(new Intent(this, HistoryActivity.class));
        });
        sidePanel.addView(panelHistory);
        Button panelAbout = secondaryButton("About");
        panelAbout.setOnClickListener(v -> {
            closePanel();
            showAbout();
        });
        sidePanel.addView(panelAbout);
        Button panelCheck = secondaryButton("Check for updates");
        panelCheck.setOnClickListener(v -> {
            closePanel();
            checkUpdate();
        });
        sidePanel.addView(panelCheck);
        Button recaver = secondaryButton("ReCaver  •  experimental");
        recaver.setOnClickListener(v -> {
            Toast.makeText(this, "ReCaver: Released soon!",
                    Toast.LENGTH_SHORT).show();
            status("ReCaver: Released soon! (CaverEngine recomp reference)");
        });
        sidePanel.addView(recaver);

        TextView credits = new TextView(this);
        credits.setText("Credits\nTemplate: Kiwi Launcher by Kizi\n"
                + "Exporter: Quantum Creeper\nAevora Labs");
        credits.setTextColor(SECONDARY);
        credits.setPadding(0, dp(16), 0, 0);
        sidePanel.addView(credits);

        root.addView(sidePanel);
        setContentView(root);
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) {
            closePanel();
        } else {
            super.onBackPressed();
        }
    }

    private void openPanel() {
        panelOpen = true;
        scrim.setVisibility(View.VISIBLE);
        sidePanel.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        panelOpen = false;
        scrim.setVisibility(View.GONE);
        sidePanel.setVisibility(View.GONE);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(p);
        c.setElevation(dp(2));
        return c;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(PURPLE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, PICK_ZIP);
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == PICK_APK) {
            new Thread(() -> {
                try {
                    byte[] bytes = readUri(uri);
                    String name = uri.getLastPathSegment();
                    if (name == null || !name.toLowerCase().endsWith(".apk")) {
                        name = "picked.apk";
                    }
                    alignAndSignApk(bytes, name);
                } catch (Exception e) {
                    runOnUiThread(() -> status("Align & sign failed: " + e.getMessage()));
                }
            }).start();
            return;
        }
        if (requestCode != PICK_ZIP) return;
        new Thread(() -> {
            try {
                byte[] bytes = readUri(uri);
                ModZip mod = ModZip.parse(bytes);
                picked = mod;
                lastExported = null;
                runOnUiThread(() -> {
                    String resultVersion = ApkPatcher.exportedVersion(mod.version);
                    modName.setText(mod.name);
                    modMeta.setText("Version " + resultVersion
                            + "\nPackage: " + mod.id
                            + "\nIcon: " + (mod.iconPng != null ? "from zip" : "template default")
                            + (mod.description != null && !mod.description.isEmpty()
                                    ? "\n" + mod.description : ""));
                    if (mod.iconPng != null) {
                        modIcon.setImageBitmap(BitmapFactory.decodeByteArray(
                                mod.iconPng, 0, mod.iconPng.length));
                    } else {
                        modIcon.setImageResource(R.mipmap.ic_launcher);
                    }
                    modCard.setVisibility(View.VISIBLE);
                    actionRow.setVisibility(View.VISIBLE);
                    exportBtn.setVisibility(View.VISIBLE);
                    installBtn.setVisibility(View.GONE);
                    shareBtn.setVisibility(View.GONE);
                    updateBtn.setVisibility(View.GONE);
                    if (!PACKAGE_RE.matcher(mod.id).matches()) {
                        status("Warning: package '" + mod.id + "' is not a valid "
                                + "Android package. Export is blocked — fix the "
                                + "mod id in properties.toml, do not ship a "
                                + "broken APK.");
                        exportBtn.setEnabled(false);
                    } else {
                        status("Ready to export as version " + resultVersion + ".");
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
                // AEE ZipAlign, deep in the flow: patch -> align -> sign.
                // Aligning BEFORE signing is what makes the v2/v3
                // signature valid over aligned data, and it removes
                // the whole class of unaligned-.so install failures
                // even if the template's packaging ever changes back.
                File toSign = unsigned;
                try {
                    File aligned = new File(cacheDir, "exported-aligned.apk");
                    ZipAlign.align(unsigned, aligned);
                    toSign = aligned;
                } catch (Exception e) {
                    // Fall back to the previous behaviour (sign
                    // unaligned) rather than failing the whole export.
                    toSign = unsigned;
                }
                File exportedDir = HistoryStore.exportedDir(this);
                File out = new File(exportedDir, mod.id + ".apk");
                Signer.sign(toSign, out);
                String resultVersion = ApkPatcher.exportedVersion(mod.version);
                HistoryStore.record(this, mod.id, mod.name, resultVersion,
                        out.getName());
                lastExported = out;
                runOnUiThread(() -> {
                    status("Exported: " + out.getName()
                            + " (version " + resultVersion + "). Opening installer…");
                    installBtn.setVisibility(View.VISIBLE);
                    shareBtn.setVisibility(View.VISIBLE);
                    updateBtn.setVisibility(View.VISIBLE);
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

    /** APK tools: strip old signatures, zipalign, re-sign, save. */
    private void alignAndSignApk(byte[] apkBytes, String originalName) throws Exception {
        runOnUiThread(() -> status("Aligning & signing " + originalName + "…"));
        File cacheDir = getCacheDir();
        File picked = new File(cacheDir, "picked.apk");
        try (java.io.OutputStream out = new java.io.FileOutputStream(picked)) {
            out.write(apkBytes);
        }
        File stripped = new File(cacheDir, "picked-stripped.apk");
        stripSignatures(picked, stripped);
        File aligned = new File(cacheDir, "picked-aligned.apk");
        ZipAlign.align(stripped, aligned);
        String base = originalName.replaceAll("(?i)\\.apk$", "")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File(HistoryStore.exportedDir(this), "aligned-" + base + ".apk");
        Signer.sign(aligned, out);
        HistoryStore.record(this, base, originalName, "aligned+resigned",
                out.getName());
        lastExported = out;
        runOnUiThread(() -> {
            status("Aligned & signed: " + out.getName() + ". Opening installer…");
            actionRow.setVisibility(View.VISIBLE);
            exportBtn.setVisibility(View.GONE);
            updateBtn.setVisibility(View.GONE);
            installBtn.setVisibility(View.VISIBLE);
            shareBtn.setVisibility(View.VISIBLE);
            install(out);
        });
    }

    /** Copy a zip APK without META-INF/* (old signatures). */
    private void stripSignatures(File in, File out) throws Exception {
        try (java.util.zip.ZipFile zin = new java.util.zip.ZipFile(in);
             java.util.zip.ZipOutputStream zout =
                     new java.util.zip.ZipOutputStream(
                             new java.io.FileOutputStream(out))) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().startsWith("META-INF/")) continue;
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(e.getName());
                byte[] data = readAll(zin.getInputStream(e));
                if (e.getMethod() == java.util.zip.ZipEntry.STORED) {
                    copy.setMethod(java.util.zip.ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    copy.setCrc(crc.getValue());
                } else {
                    copy.setMethod(java.util.zip.ZipEntry.DEFLATED);
                }
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    private byte[] readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
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

    private void share(File apk) {
        Uri uri = Uri.parse("content://" + getPackageName()
                + ".fileprovider/exported/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share " + apk.getName()));
    }

    private void openApp(String pkg, String label) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, label + " is not installed.",
                    Toast.LENGTH_SHORT).show();
            status(label + " is not installed on this phone.");
            return;
        }
        closePanel();
        startActivity(launch);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("About Aevora Exporter")
                .setMessage("Aevora Exporter v9\n"
                        + "Template: Kiwi Launcher 9.0\n\n"
                        + "Turns a Kiwi Launcher zip mod into a standalone "
                        + "APK that boots straight into that one mod.\n\n"
                        + "Version rules:\n"
                        + "• This app: v9, matching the Kiwi template.\n"
                        + "• Exported APKs: <mod version>_kiwi9, "
                        + "e.g. 1.4.5_kiwi9.\n\n"
                        + "Credits:\n"
                        + "• Template: Kiwi Launcher by Kizi\n"
                        + "• Exporter: Quantum Creeper\n"
                        + "• Aevora Labs\n\n"
                        + "Limits: exported APKs still need vanilla "
                        + "Swordigo installed, exactly like the launcher. "
                        + "Each exported APK gets its own saves, not the "
                        + "launcher's.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void checkUpdate() {
        status("Checking for updates…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_URL)
                        .openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "AevoraExporter/9");
                if (c.getResponseCode() != 200) {
                    throw new IllegalStateException("server returned "
                            + c.getResponseCode());
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] tmp = new byte[1024];
                    int n;
                    while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                }
                String raw = buf.toString("UTF-8").trim();
                int remote = Integer.parseInt(raw.split("\\.")[0].trim());
                runOnUiThread(() -> {
                    if (remote > LOCAL_MAJOR) {
                        status("Update available: v" + remote
                                + " (you have v" + LOCAL_MAJOR + ").");
                        new AlertDialog.Builder(this)
                                .setTitle("Update available")
                                .setMessage("Aevora Exporter v" + remote
                                        + " is available. You have v"
                                        + LOCAL_MAJOR + ".\n\n"
                                        + "No download link here — update "
                                        + "manually from the repo when "
                                        + "you are ready.")
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        status("You are up to date (v" + LOCAL_MAJOR + ").");
                        Toast.makeText(this,
                                "You are up to date (v" + LOCAL_MAJOR + ").",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Could not check for updates: "
                        + e.getMessage()));
            }
        }).start();
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
