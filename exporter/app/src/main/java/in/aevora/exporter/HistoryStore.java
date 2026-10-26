package in.aevora.exporter;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Export history: every APK the exporter produces is recorded here so
 * it can be installed or shared again any day, or deleted. Storage is
 * a plain JSON list next to the APKs themselves, so deleting the app
 * data clears history too — history is a convenience, not a backup.
 *
 * Entry: {id, name, version, file, exportedAt}. "file" is just the
 * APK filename inside <externalFiles>/exported/, never a full path.
 */
public class HistoryStore {

    public static class Entry {
        public String id, name, version, file;
        public long exportedAt;
    }

    public static File exportedDir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "exported");
        d.mkdirs();
        return d;
    }

    public static File historyFile(Context ctx) {
        return new File(exportedDir(ctx), "history.json");
    }

    public static List<Entry> load(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            File f = historyFile(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return out;
            }
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id");
                e.name = o.optString("name");
                e.version = o.optString("version");
                e.file = o.optString("file");
                e.exportedAt = o.optLong("exportedAt");
                if (e.file != null && !e.file.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context ctx, List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id == null ? "" : e.id);
                o.put("name", e.name == null ? "" : e.name);
                o.put("version", e.version == null ? "" : e.version);
                o.put("file", e.file);
                o.put("exportedAt", e.exportedAt);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(historyFile(ctx))) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    /** Newest first, one entry per package id (re-export updates it). */
    public static void record(Context ctx, String id, String name,
                              String version, String file) {
        List<Entry> entries = load(ctx);
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.id.equals(id)) kept.add(e);
        }
        Entry e = new Entry();
        e.id = id;
        e.name = name;
        e.version = version;
        e.file = file;
        e.exportedAt = System.currentTimeMillis();
        kept.add(0, e);
        save(ctx, kept);
    }

    public static void remove(Context ctx, Entry entry) {
        List<Entry> entries = load(ctx);
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.file.equals(entry.file)) kept.add(e);
        }
        save(ctx, kept);
        File apk = new File(exportedDir(ctx), entry.file);
        if (apk.exists()) apk.delete();
    }
}
