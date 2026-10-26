package net.kiwi.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Aevora template boot: single-mod mode, kept as one new file so upstream
 * edits overlay cleanly. No hard-coded mod id: the id is read from the
 * bundled zip's own properties.toml.
 *
 * Contract:
 * - If assets/bundled_mod.zip is absent, this class reports "not template
 *   mode" and the caller must behave exactly like the normal launcher.
 * - If present, installBundledMod() extracts it to
 *   <externalFiles>/mods/<id>/, hash-gated (files/bundled_mod.hash), and
 *   preserves saves/ and data/ across updates, mirroring ModManager.
 * - On any failure, installBundledMod() returns null and the caller falls
 *   back to the normal launcher UI, never a black screen.
 */
public class TemplateBoot {

    private static final String TAG = "TemplateBoot";
    private static final String BUNDLED_ASSET = "bundled_mod.zip";
    private static final String HASH_FILE = "bundled_mod.hash";

    private static Boolean sHasBundled;

    public static boolean isTemplateMode(Context context) {
        if (sHasBundled != null) return sHasBundled;
        try (InputStream in = context.getAssets().open(BUNDLED_ASSET)) {
            sHasBundled = true;
        } catch (IOException e) {
            sHasBundled = false;
        }
        return sHasBundled;
    }

    /** Synchronous install. Returns the installed ModInfo, or null on failure. */
    public static ModManager.ModInfo installBundledMod(Context context) {
        try {
            File cacheZip = new File(context.getCacheDir(), BUNDLED_ASSET);
            copyAsset(context, BUNDLED_ASSET, cacheZip);
            String hash = sha256(cacheZip);

            File staging = new File(context.getCacheDir(), "bundled_staging_" + System.currentTimeMillis());
            ensureDir(staging);
            try {
                extractZip(cacheZip, staging);
                File propsFile = new File(staging, "properties.toml");
                if (!propsFile.exists()) {
                    Log.e(TAG, "bundled mod missing properties.toml");
                    return null;
                }
                Map<String, String> map = Util.parseTomlSection(propsFile, "mod");
                String id = map.get("id");
                if (id == null || id.isEmpty()) {
                    Log.e(TAG, "bundled properties.toml missing [mod] id");
                    return null;
                }
                File modsDir = new File(context.getExternalFilesDir(null), "mods");
                ensureDir(modsDir);
                File targetDir = new File(modsDir, sanitize(id));

                // Hash-gated: same zip as last time and target already there
                // means user edits in the target folder must not be clobbered.
                String lastHash = readHash(context);
                if (hash.equals(lastHash) && new File(targetDir, "properties.toml").exists()) {
                    ModManager.ModInfo info = new ModManager.ModInfo();
                    info.id = id;
                    info.name = map.get("name") != null ? map.get("name") : id;
                    info.version = map.get("version");
                    info.description = map.get("description");
                    info.dir = targetDir;
                    return info;
                }

                // Preserve saves/ and data/ across a bundled update.
                File backup = new File(context.getCacheDir(), "bundled_preserve_" + System.currentTimeMillis());
                for (String name : new String[]{"saves", "data"}) {
                    File src = new File(targetDir, name);
                    if (src.isDirectory()) copyRecursive(src, new File(backup, name));
                }
                deleteRecursive(targetDir);
                ensureDir(targetDir);
                copyRecursive(staging, targetDir);
                for (String name : new String[]{"saves", "data"}) {
                    File src = new File(backup, name);
                    if (src.isDirectory()) {
                        deleteRecursive(new File(targetDir, name));
                        copyRecursive(src, new File(targetDir, name));
                    }
                }
                deleteRecursive(backup);
                writeHash(context, hash);

                ModManager.ModInfo info = new ModManager.ModInfo();
                info.id = id;
                info.name = map.get("name") != null ? map.get("name") : id;
                info.version = map.get("version");
                info.description = map.get("description");
                info.dir = targetDir;
                return info;
            } finally {
                deleteRecursive(staging);
            }
        } catch (Exception e) {
            Log.e(TAG, "installBundledMod failed", e);
            return null;
        }
    }

    private static String sanitize(String raw) {
        return raw.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void copyAsset(Context context, String asset, File dst) throws IOException {
        try (InputStream in = context.getAssets().open(asset);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String readHash(Context context) {
        try {
            File f = new File(context.getFilesDir(), HASH_FILE);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n).trim() : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeHash(Context context, String hash) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), HASH_FILE))) {
            out.write(hash.getBytes());
        } catch (Exception ignored) {
        }
    }

    private static void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                // Zip-slip guard: stay inside destDir.
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)
                        && !out.getCanonicalPath().equals(destDir.getCanonicalPath())) {
                    throw new IOException("zip entry escapes dest dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    ensureDir(out);
                } else {
                    ensureDir(out.getParentFile());
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists()) dir.mkdirs();
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            ensureDir(dst);
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File kid : kids) copyRecursive(kid, new File(dst, kid.getName()));
            }
        } else {
            ensureDir(dst.getParentFile());
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRecursive(kid);
            }
        }
        return f.delete();
    }
}
