package in.aevora.exporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aevora Exporter v9 — complete GUI rewrite (2026-10-26).
 *
 * Why a rewrite: the previous single-scroll layout had a real bug —
 * the action row put MATCH_PARENT buttons inside a horizontal row,
 * so Export took the whole row and Install/Share/Update were
 * squeezed off-screen, and the status text scrolled away. This
 * version is a proper multi-panel app:
 *
 * - Top bar (menu + title + v9 chip, fixed at the top).
 * - Four panels, one visible at a time: Home, History, APK Tools,
 *   About. Switched from the bottom nav OR the side drawer.
 * - Side drawer (left, slides in, scrim to close): same four
 *   panels + Open RubyTouch + Open Launcher + Check for updates +
 *   credits.
 * - Bottom progress bar, FIXED at the bottom (never scrolled away,
 *   never removed): stage text + determinate progress. Layout
 *   height is fixed, so starting/stopping work never jumps the UI.
 *
 * Export stages, always in this order, every time, no exceptions:
 *   Patch -> Zipalign (MANDATORY) -> Sign -> Save -> Done.
 * If zipalign fails, the export FAILS with a clear error — it never
 * silently ships an unaligned APK. The Python reference patcher
 * does the same (zipalign -f 4, check=True, even on --skip-sign).
 *
 * Framework-only, no androidx. Version rule: this app is v9
 * (template Kiwi 9.0); exported APKs are <mod>_kiwi9.
 */
public class MainActivity extends Activity {

    private static final int PICK_ZIP = 1;
    private static final int PICK_APK = 2;
    private static final Pattern PACKAGE_RE =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/TheAevoraLabs/AevoraExporter"
            + "/main/_database/version.txt";
    private static final int LOCAL_MAJOR = 9;

    private static final int PURPLE = 0xFF6750A4;
    private static final int SURFACE = 0xFFF6F2FA;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ON_SURFACE = 0xFF1D1B20;
    private static final int SECONDARY = 0xFF49454F;
    private static final int TEAL = 0xFF7CE7C4;

    private static final int PANEL_HOME = 0;
    private static final int PANEL_HISTORY = 1;
    private static final int PANEL_TOOLS = 2;
    private static final int PANEL_ABOUT = 3;

    private View[] panels;
    private Button[] navButtons;
    private TextView topTitle;
    private ProgressBar progressBar;
    private TextView stageText;

    // Home panel
    private LinearLayout modCard;
    private ImageView modIcon;
    private TextView modName, modMeta;
    private Button exportBtn;
    private LinearLayout postRow;
    private Button installBtn, shareBtn, updateBtn;
    private ModZip picked;
    private File lastExported;

    // Tools panel
    private LinearLayout toolsPostRow;
    private Button toolsInstallBtn, toolsShareBtn;

    // History panel
    private LinearLayout historyList;
    private TextView historyEmpty;

    // Drawer
    private LinearLayout drawer;
    private View scrim;
    private boolean drawerOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);

        // ---- Main column: top bar / panels / progress / bottom nav ----
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        // Top bar, fixed
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PURPLE);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button menu = new Button(this);
        menu.setText("\u2630");
        menu.setTextSize(20);
        menu.setTextColor(0xFFFFFFFF);
        menu.setBackgroundColor(0x00000000);
        menu.setPadding(dp(12), dp(8), dp(12), dp(8));
        menu.setOnClickListener(v -> openDrawer());
        top.addView(menu);
        topTitle = new TextView(this);
        topTitle.setText("Home");
        topTitle.setTextSize(20);
        topTitle.setTypeface(null, Typeface.BOLD);
        topTitle.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topTitle.setLayoutParams(tp);
        top.addView(topTitle);
        TextView chip = new TextView(this);
        chip.setText("v9");
        chip.setTextColor(0xFF1D1B20);
        chip.setBackgroundColor(TEAL);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        main.addView(top);

        // Panels container, weight 1
        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        content.setLayoutParams(cp);
        panels = new View[4];
        panels[PANEL_HOME] = buildHomePanel();
        panels[PANEL_HISTORY] = buildHistoryPanel();
        panels[PANEL_TOOLS] = buildToolsPanel();
        panels[PANEL_ABOUT] = buildAboutPanel();
        for (View p : panels) content.addView(p);
        main.addView(content);

        // Progress bar, FIXED at bottom, fixed height, never removed.
        // When idle the bar is invisible but the row stays, so the
        // layout never jumps when work starts or stops.
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.VERTICAL);
        progressRow.setBackgroundColor(CARD);
        progressRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        stageText = new TextView(this);
        stageText.setText("Ready.");
        stageText.setTextColor(SECONDARY);
        progressRow.addView(stageText);
        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        progressRow.addView(progressBar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(progressRow);

        // Bottom nav, fixed
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(CARD);
        nav.setElevation(dp(4));
        String[] labels = {"Home", "History", "Tools", "About"};
        navButtons = new Button[4];
        for (int i = 0; i < 4; i++) {
            final int panel = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setBackgroundColor(0x00000000);
            b.setPadding(0, dp(12), 0, dp(12));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            b.setLayoutParams(bp);
            b.setOnClickListener(v -> showPanel(panel));
            navButtons[i] = b;
            nav.addView(b);
        }
        main.addView(nav);

        root.addView(main);

        // ---- Drawer overlay ----
        scrim = new View(this);
        scrim.setBackgroundColor(0x80000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim);

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(CARD);
        drawer.setPadding(dp(16), dp(16), dp(16), dp(16));
        drawer.setVisibility(View.GONE);
        drawer.setElevation(dp(8));
        FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(
                dp(300), FrameLayout.LayoutParams.MATCH_PARENT);
        dp2.gravity = Gravity.START;
        drawer.setLayoutParams(dp2);
        drawer.setTranslationX(-dp(300));

        ImageView dIcon = new ImageView(this);
        dIcon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams dip = new LinearLayout.LayoutParams(dp(56), dp(56));
        dIcon.setLayoutParams(dip);
        drawer.addView(dIcon);
        TextView dTitle = new TextView(this);
        dTitle.setText("Aevora Exporter");
        dTitle.setTextSize(20);
        dTitle.setTypeface(null, Typeface.BOLD);
        dTitle.setTextColor(ON_SURFACE);
        drawer.addView(dTitle);
        TextView dSub = new TextView(this);
        dSub.setText("v9  •  Template: Kiwi Launcher 9.0");
        dSub.setTextColor(SECONDARY);
        drawer.addView(dSub);

        drawer.addView(drawerItem("Home", () -> showPanel(PANEL_HOME)));
        drawer.addView(drawerItem("History", () -> showPanel(PANEL_HISTORY)));
        drawer.addView(drawerItem("APK Tools", () -> showPanel(PANEL_TOOLS)));
        drawer.addView(drawerItem("About", () -> showPanel(PANEL_ABOUT)));
        drawer.addView(drawerItem("Open RubyTouch",
                () -> openApp("in.aevora.ruby", "RubyTouch")));
        drawer.addView(drawerItem("Open Launcher (Kiwi)",
                () -> openApp("net.kiwi.launcher", "Kiwi Launcher")));
        drawer.addView(drawerItem("Check for updates", this::checkUpdate));
        TextView credits = new TextView(this);
        credits.setText("Credits\nTemplate: Kiwi Launcher by Kizi\n"
                + "Exporter: Quantum Creeper\nAevora Labs\n"
                + "ZipAlign: APK Explorer & Editor (GPLv3)");
        credits.setTextColor(SECONDARY);
        credits.setPadding(0, dp(16), 0, 0);
        drawer.addView(credits);

        root.addView(drawer);
        setContentView(root);
        showPanel(PANEL_HOME);
    }

    // ================= Panels =================

    private ScrollView buildHomePanel() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);

        LinearLayout importCard = card();
        importCard.addView(cardTitle("Import a mod"));
        TextView hint = new TextView(this);
        hint.setText("Pick a Kiwi Launcher zip mod. The exporter patches "
                + "the bundled template APK with that mod's package, name, "
                + "version and icon, then zipaligns and signs it.");
        hint.setTextColor(SECONDARY);
        importCard.addView(hint);
        Button pick = fullButton("Pick mod zip", PURPLE, 0xFFFFFFFF);
        pick.setOnClickListener(v -> pickZip());
        importCard.addView(pick);
        Button shaders = fullButton("Shaders  •  experimental", 0xFFE0E0E0, ON_SURFACE);
        shaders.setOnClickListener(v -> {
            Toast.makeText(this, "Shaders will be available soon!",
                    Toast.LENGTH_SHORT).show();
            setStage("Shaders: will be available soon!", 0);
        });
        importCard.addView(shaders);
        col.addView(importCard);

        modCard = card();
        modCard.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        modIcon = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(96), dp(96));
        ip.setMargins(0, 0, dp(12), 0);
        modIcon.setLayoutParams(ip);
        row.addView(modIcon);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        modName = new TextView(this);
        modName.setTextSize(19);
        modName.setTypeface(null, Typeface.BOLD);
        modName.setTextColor(ON_SURFACE);
        text.addView(modName);
        modMeta = new TextView(this);
        modMeta.setTextColor(SECONDARY);
        text.addView(modMeta);
        row.addView(text);
        modCard.addView(row);
        col.addView(modCard);

        LinearLayout exportCard = card();
        exportCard.addView(cardTitle("Export"));
        TextView note = new TextView(this);
        note.setText("Every export is zipaligned before signing, every "
                + "time. If zipalign fails, the export fails — it never "
                + "ships an unaligned APK.");
        note.setTextColor(SECONDARY);
        exportCard.addView(note);
        exportBtn = fullButton("Export APK", PURPLE, 0xFFFFFFFF);
        exportBtn.setTextSize(18);
        exportBtn.setOnClickListener(v -> exportApk());
        exportCard.addView(exportBtn);

        postRow = new LinearLayout(this);
        postRow.setOrientation(LinearLayout.HORIZONTAL);
        postRow.setVisibility(View.GONE);
        installBtn = rowButton("Install");
        installBtn.setOnClickListener(v -> {
            if (lastExported != null) install(lastExported);
        });
        shareBtn = rowButton("Share");
        shareBtn.setOnClickListener(v -> {
            if (lastExported != null) share(lastExported);
        });
        updateBtn = rowButton("Update");
        updateBtn.setOnClickListener(v -> exportApk());
        postRow.addView(installBtn);
        postRow.addView(shareBtn);
        postRow.addView(updateBtn);
        exportCard.addView(postRow);
        col.addView(exportCard);

        return scroll;
    }

    private ScrollView buildHistoryPanel() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);
        col.addView(cardTitle("Export history"));
        TextView hint = new TextView(this);
        hint.setText("APKs stay in the exporter's files folder. Install or "
                + "share them again any day, or delete them here.");
        hint.setTextColor(SECONDARY);
        col.addView(hint);
        historyEmpty = new TextView(this);
        historyEmpty.setText("Nothing exported yet. Export a mod from Home first.");
        historyEmpty.setTextColor(SECONDARY);
        col.addView(historyEmpty);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyList);
        return scroll;
    }

    private ScrollView buildToolsPanel() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);

        LinearLayout tools = card();
        tools.addView(cardTitle("APK tools"));
        TextView hint = new TextView(this);
        hint.setText("Pick any APK: old signatures are stripped, it is "
                + "zipaligned (mandatory, every time) and re-signed with "
                + "this device's key, then saved to History. This fixes "
                + "APKs that fail install with alignment errors.");
        hint.setTextColor(SECONDARY);
        tools.addView(hint);
        Button align = fullButton("Pick APK to align & sign", PURPLE, 0xFFFFFFFF);
        align.setOnClickListener(v -> pickApk());
        tools.addView(align);
        toolsPostRow = new LinearLayout(this);
        toolsPostRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsPostRow.setVisibility(View.GONE);
        toolsInstallBtn = rowButton("Install");
        toolsInstallBtn.setOnClickListener(v -> {
            if (lastExported != null) install(lastExported);
        });
        toolsShareBtn = rowButton("Share");
        toolsShareBtn.setOnClickListener(v -> {
            if (lastExported != null) share(lastExported);
        });
        toolsPostRow.addView(toolsInstallBtn);
        toolsPostRow.addView(toolsShareBtn);
        tools.addView(toolsPostRow);
        col.addView(tools);

        LinearLayout apps = card();
        apps.addView(cardTitle("Open apps"));
        Button ruby = fullButton("Open RubyTouch", 0xFFE0E0E0, ON_SURFACE);
        ruby.setOnClickListener(v -> openApp("in.aevora.ruby", "RubyTouch"));
        apps.addView(ruby);
        Button launcher = fullButton("Open Launcher (Kiwi)", 0xFFE0E0E0, ON_SURFACE);
        launcher.setOnClickListener(v -> openApp("net.kiwi.launcher", "Kiwi Launcher"));
        apps.addView(launcher);
        col.addView(apps);

        LinearLayout exp = card();
        exp.addView(cardTitle("Experimental"));
        Button recaver = fullButton("ReCaver  •  experimental", 0xFFE0E0E0, ON_SURFACE);
        recaver.setOnClickListener(v -> {
            Toast.makeText(this, "ReCaver: Released soon!",
                    Toast.LENGTH_SHORT).show();
            setStage("ReCaver: Released soon! (CaverEngine recomp reference)", 0);
        });
        exp.addView(recaver);
        col.addView(exp);
        return scroll;
    }

    private ScrollView buildAboutPanel() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);
        col.addView(cardTitle("About Aevora Exporter"));
        TextView about = new TextView(this);
        about.setText("Aevora Exporter v9\n"
                + "Template: Kiwi Launcher 9.0\n\n"
                + "Turns a Kiwi Launcher zip mod into a standalone APK "
                + "that boots straight into that one mod.\n\n"
                + "Version rules:\n"
                + "• This app: v9, matching the Kiwi template.\n"
                + "• Exported APKs: <mod version>_kiwi9, e.g. 1.4.5_kiwi9.\n\n"
                + "Sanitised every time:\n"
                + "• Patch -> Zipalign (mandatory) -> Sign -> Save.\n"
                + "• A failed zipalign fails the export, never a "
                + "silent unaligned APK.\n\n"
                + "Credits:\n"
                + "• Template: Kiwi Launcher by Kizi\n"
                + "• Exporter: Quantum Creeper\n"
                + "• Aevora Labs\n"
                + "• ZipAlign: APK Explorer & Editor (GPLv3)\n\n"
                + "Limits: exported APKs still need vanilla Swordigo "
                + "installed, exactly like the launcher. Each exported "
                + "APK gets its own saves, not the launcher's.");
        about.setTextColor(ON_SURFACE);
        col.addView(about);
        Button check = fullButton("Check for updates", PURPLE, 0xFFFFFFFF);
        check.setOnClickListener(v -> checkUpdate());
        col.addView(check);
        return scroll;
    }

    // ================= Panel switching / drawer =================

    private void showPanel(int panel) {
        closeDrawer();
        String[] titles = {"Home", "History", "APK Tools", "About"};
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == panel ? View.VISIBLE : View.GONE);
            navButtons[i].setTextColor(i == panel ? PURPLE : SECONDARY);
            navButtons[i].setTypeface(null, i == panel ? Typeface.BOLD : Typeface.NORMAL);
        }
        topTitle.setText(titles[panel]);
        if (panel == PANEL_HISTORY) refreshHistory();
    }

    private Button drawerItem(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackgroundColor(0x00000000);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setOnClickListener(v -> {
            closeDrawer();
            action.run();
        });
        return b;
    }

    private void openDrawer() {
        drawerOpen = true;
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(200).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        scrim.setVisibility(View.GONE);
        drawer.animate().translationX(-dp(300)).setDuration(200)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
        } else {
            super.onBackPressed();
        }
    }

    // ================= Helpers =================

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

    private TextView cardTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(ON_SURFACE);
        return t;
    }

    /** Full-width button for vertical stacks only, never in a row. */
    private Button fullButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    /** Weighted button for horizontal rows: equal share, never MATCH_PARENT. */
    private Button rowButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(p);
        return b;
    }

    /** Progress stage: text + determinate bar. Bar stays in layout
     *  (invisible when idle) so the UI never jumps. */
    private void setStage(String text, int progress) {
        stageText.setText(text);
        progressBar.setProgress(progress);
        progressBar.setVisibility(progress > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void setStageOnUi(String text, int progress) {
        runOnUiThread(() -> setStage(text, progress));
    }

    // ================= Pick / export / tools =================

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
                    setStageOnUi("Align & sign failed: " + e.getMessage(), 0);
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
                    postRow.setVisibility(View.GONE);
                    if (!PACKAGE_RE.matcher(mod.id).matches()) {
                        setStage("Warning: package '" + mod.id + "' is not a valid "
                                + "Android package. Export is blocked — fix the "
                                + "mod id in properties.toml.", 0);
                        exportBtn.setEnabled(false);
                        exportBtn.setAlpha(0.5f);
                    } else {
                        setStage("Ready to export as version " + resultVersion + ".", 0);
                        exportBtn.setEnabled(true);
                        exportBtn.setAlpha(1f);
                    }
                    showPanel(PANEL_HOME);
                });
            } catch (Exception e) {
                setStageOnUi("Could not read mod zip: " + e.getMessage(), 0);
            }
        }).start();
    }

    private void exportApk() {
        if (picked == null) return;
        exportBtn.setEnabled(false);
        exportBtn.setAlpha(0.5f);
        final ModZip mod = picked;
        new Thread(() -> {
            try {
                setStageOnUi("Stage 1/4: Patching template…", 25);
                File cacheDir = getCacheDir();
                File templateFile = new File(cacheDir, "template.apk");
                copyAsset("template.apk", templateFile);
                File unsigned = new File(cacheDir, "exported-unsigned.apk");
                ApkPatcher.patch(templateFile, mod, unsigned);

                // Stage 2 is MANDATORY. No fallback: a zipalign failure
                // fails the export, it never ships unaligned.
                setStageOnUi("Stage 2/4: Zipaligning (mandatory)…", 50);
                File aligned = new File(cacheDir, "exported-aligned.apk");
                ZipAlign.align(unsigned, aligned);

                setStageOnUi("Stage 3/4: Signing…", 75);
                File exportedDir = HistoryStore.exportedDir(this);
                File out = new File(exportedDir, mod.id + ".apk");
                Signer.sign(aligned, out);

                String resultVersion = ApkPatcher.exportedVersion(mod.version);
                HistoryStore.record(this, mod.id, mod.name, resultVersion,
                        out.getName());
                lastExported = out;
                runOnUiThread(() -> {
                    setStage("Done: " + out.getName()
                            + " (version " + resultVersion + "). Opening installer…", 100);
                    postRow.setVisibility(View.VISIBLE);
                    install(out);
                    exportBtn.setEnabled(true);
                    exportBtn.setAlpha(1f);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStage("Export failed: " + e.getMessage(), 0);
                    exportBtn.setEnabled(true);
                    exportBtn.setAlpha(1f);
                });
            }
        }).start();
    }

    private void alignAndSignApk(byte[] apkBytes, String originalName) throws Exception {
        setStageOnUi("Stage 1/3: Stripping old signatures…", 20);
        File cacheDir = getCacheDir();
        File pickedFile = new File(cacheDir, "picked.apk");
        try (java.io.OutputStream out = new java.io.FileOutputStream(pickedFile)) {
            out.write(apkBytes);
        }
        File stripped = new File(cacheDir, "picked-stripped.apk");
        stripSignatures(pickedFile, stripped);
        setStageOnUi("Stage 2/3: Zipaligning (mandatory)…", 50);
        File aligned = new File(cacheDir, "picked-aligned.apk");
        ZipAlign.align(stripped, aligned);
        setStageOnUi("Stage 3/3: Signing…", 80);
        String base = originalName.replaceAll("(?i)\\.apk$", "")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File(HistoryStore.exportedDir(this), "aligned-" + base + ".apk");
        Signer.sign(aligned, out);
        HistoryStore.record(this, base, originalName, "aligned+resigned",
                out.getName());
        lastExported = out;
        runOnUiThread(() -> {
            setStage("Done: " + out.getName() + ". Opening installer…", 100);
            toolsPostRow.setVisibility(View.VISIBLE);
            showPanel(PANEL_TOOLS);
            install(out);
        });
    }

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

    // ================= History panel =================

    private void refreshHistory() {
        historyList.removeAllViews();
        List<HistoryStore.Entry> entries = HistoryStore.load(this);
        historyEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (HistoryStore.Entry e : entries) {
            LinearLayout card = card();
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(17);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(ON_SURFACE);
            card.addView(name);
            TextView meta = new TextView(this);
            meta.setText(e.id + "\nVersion " + e.version + "  •  "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT,
                            DateFormat.SHORT).format(new java.util.Date(e.exportedAt)));
            meta.setTextColor(SECONDARY);
            card.addView(meta);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button install = rowButton("Install");
            install.setOnClickListener(v -> {
                File apk = new File(HistoryStore.exportedDir(this), e.file);
                if (apk.exists()) install(apk);
                else Toast.makeText(this, "APK file is gone, re-export it.",
                        Toast.LENGTH_SHORT).show();
            });
            Button share = rowButton("Share");
            share.setOnClickListener(v -> {
                File apk = new File(HistoryStore.exportedDir(this), e.file);
                if (apk.exists()) share(apk);
                else Toast.makeText(this, "APK file is gone, re-export it.",
                        Toast.LENGTH_SHORT).show();
            });
            Button del = rowButton("Delete");
            del.setOnClickListener(v -> {
                HistoryStore.remove(this, e);
                Toast.makeText(this, "Deleted " + e.file, Toast.LENGTH_SHORT).show();
                refreshHistory();
            });
            row.addView(install);
            row.addView(share);
            row.addView(del);
            card.addView(row);
            historyList.addView(card);
        }
    }

    // ================= Misc =================

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
            setStage(label + " is not installed on this phone.", 0);
            return;
        }
        closeDrawer();
        startActivity(launch);
    }

    private void checkUpdate() {
        setStage("Checking for updates…", 25);
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
                        setStage("Update available: v" + remote
                                + " (you have v" + LOCAL_MAJOR + ").", 100);
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
                        setStage("You are up to date (v" + LOCAL_MAJOR + ").", 100);
                        Toast.makeText(this,
                                "You are up to date (v" + LOCAL_MAJOR + ").",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                setStageOnUi("Could not check for updates: " + e.getMessage(), 0);
            }
        }).start();
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

    private byte[] readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
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
