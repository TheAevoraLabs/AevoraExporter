package net.aevora.exporter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.List;

/** History: every exported APK, install / share / delete, any day. */
public class HistoryActivity extends Activity {

    private LinearLayout list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F2FA);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Export history");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1D1B20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("APKs stay in the exporter's files folder. Install or "
                + "share them again any day, or delete them here.");
        hint.setTextColor(0xFF49454F);
        root.addView(hint);

        empty = new TextView(this);
        empty.setText("Nothing exported yet. Pick a mod zip in the main "
                + "screen and export it first.");
        empty.setTextColor(0xFF49454F);
        root.addView(empty);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<HistoryStore.Entry> entries = HistoryStore.load(this);
        empty.setVisibility(entries.isEmpty() ? android.view.View.VISIBLE
                : android.view.View.GONE);
        for (HistoryStore.Entry e : entries) {
            LinearLayout card = card();
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(17);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(0xFF1D1B20);
            card.addView(name);
            TextView meta = new TextView(this);
            meta.setText(e.id + "\nVersion " + e.version + "  •  "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT,
                            DateFormat.SHORT).format(new java.util.Date(e.exportedAt)));
            meta.setTextColor(0xFF49454F);
            card.addView(meta);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button install = smallButton("Install");
            install.setOnClickListener(v -> installApk(e));
            Button share = smallButton("Share");
            share.setOnClickListener(v -> shareApk(e));
            Button del = smallButton("Delete");
            del.setOnClickListener(v -> {
                HistoryStore.remove(this, e);
                Toast.makeText(this, "Deleted " + e.file, Toast.LENGTH_SHORT).show();
                refresh();
            });
            row.addView(install);
            row.addView(share);
            row.addView(del);
            card.addView(row);
            list.addView(card);
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFFFFFFFF);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(p);
        c.setElevation(dp(2));
        return c;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(p);
        return b;
    }

    private void installApk(HistoryStore.Entry e) {
        File apk = new File(HistoryStore.exportedDir(this), e.file);
        if (!apk.exists()) {
            Toast.makeText(this, "APK file is gone, re-export it.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("content://" + getPackageName()
                + ".fileprovider/exported/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void shareApk(HistoryStore.Entry e) {
        File apk = new File(HistoryStore.exportedDir(this), e.file);
        if (!apk.exists()) {
            Toast.makeText(this, "APK file is gone, re-export it.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("content://" + getPackageName()
                + ".fileprovider/exported/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share " + e.name));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
