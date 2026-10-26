package net.aevora.exporter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * On-device port of tools/patcher.py steps 2-5 (signing is Signer.java).
 *
 * - Copies the template APK, skipping old META-INF signatures.
 * - Replaces assets/bundled_mod.zip with the input mod zip.
 * - Rewrites AndroidManifest.xml via AxmlPatcher: package (exact + prefix),
 *   label, versionName.
 * - Patches resources.arsc package name via AxmlPatcher.
 * - Replaces icon image entries (ic_launcher* .png/.webp, not .xml) with
 *   the mod's icon.png, resized to each entry's dimensions and converted
 *   to that entry's format.
 *
 * FIX 2026-10-26 (install failure): this patcher used to force every
 * .so entry to STORED with no alignment pass. With the template's
 * extractNativeLibs=false, Android's installer requires uncompressed
 * .so files to be page-aligned, and unaligned output fails install
 * with a bare "App not installed". The template now uses legacy
 * packaging (compressed .so, extracted at install), and this patcher
 * preserves each entry's original compression method instead of
 * forcing STORED, so the output inherits the template's
 * install-safe packaging.
 */
public class ApkPatcher {

    public static final String OLD_PACKAGE = "net.aevora.template";
    public static final String OLD_LABEL = "Aevora Template";
    public static final String OLD_VERSION = "9.0";
    /** Kiwi template major version, from OLD_VERSION ("9.0" -> "9"). */
    public static final String TEMPLATE_MAJOR = "9";

    /**
     * Exported APK versionName rule (2026-10-26): the result carries the
     * MOD's version, not the Kiwi version, with the Kiwi template major
     * as a suffix — e.g. mod 1.4.5 on Kiwi 9 -> "1.4.5_kiwi9".
     * Already-suffixed versions are left alone; a missing mod version
     * falls back to the template version.
     */
    public static String exportedVersion(String modVersion) {
        if (modVersion == null || modVersion.trim().isEmpty()) return OLD_VERSION;
        String v = modVersion.trim();
        if (v.contains("_kiwi")) return v;
        return v + "_kiwi" + TEMPLATE_MAJOR;
    }

    public static void patch(File templateApk, ModZip mod, File outApk) throws IOException {
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        try (ZipFile zin = new ZipFile(templateApk)) {
            java.util.Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("META-INF/")) continue;
                methods.add(e.getMethod());
                byte[] data = readAll(zin.getInputStream(e));
                if (e.getName().equals("AndroidManifest.xml")) {
                    data = patchManifest(data, mod);
                } else if (e.getName().equals("resources.arsc")) {
                    data = AxmlPatcher.patchArscPackage(data, OLD_PACKAGE, mod.id);
                } else if (mod.iconPng != null && isIconEntry(e.getName())) {
                    byte[] converted = convertIcon(mod.iconPng, e.getName(), data);
                    if (converted != null) data = converted;
                }
                names.add(e.getName());
                datas.add(data);
            }
        }
        // Bundled mod: replace or add
        int idx = names.indexOf("assets/bundled_mod.zip");
        if (idx >= 0) {
            datas.set(idx, mod.zipBytes);
        } else {
            names.add("assets/bundled_mod.zip");
            datas.add(mod.zipBytes);
            methods.add(ZipEntry.DEFLATED);
        }
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outApk))) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                byte[] data = datas.get(i);
                ZipEntry e = new ZipEntry(name);
                // Preserve the template's method: with legacy packaging
                // the .so files are DEFLATED and extracted at install,
                // so forcing STORED here would reintroduce the
                // unaligned-native-lib install failure.
                if (methods.get(i) == ZipEntry.STORED) {
                    e.setMethod(ZipEntry.STORED);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    e.setCrc(crc(data));
                } else {
                    e.setMethod(ZipEntry.DEFLATED);
                }
                zout.putNextEntry(e);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    private static byte[] patchManifest(byte[] data, ModZip mod) {
        List<String> strings = AxmlPatcher.readStrings(data);
        Map<String, String> repl = new HashMap<>();
        for (String s : strings) {
            if (s.equals(OLD_PACKAGE) || s.startsWith(OLD_PACKAGE + ".")) {
                repl.put(s, mod.id + s.substring(OLD_PACKAGE.length()));
            }
            if (s.equals(OLD_LABEL)) repl.put(s, mod.name);
            if (s.equals(OLD_VERSION)) repl.put(s, exportedVersion(mod.version));
        }
        return AxmlPatcher.patch(data, repl);
    }

    private static boolean isIconEntry(String name) {
        if (!name.startsWith("res/")) return false;
        if (!(name.endsWith(".png") || name.endsWith(".webp"))) return false;
        return name.contains("ic_launcher");
    }

    private static byte[] convertIcon(byte[] iconPng, String entryName, byte[] original) {
        try {
            Bitmap src = BitmapFactory.decodeByteArray(iconPng, 0, iconPng.length);
            if (src == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(original, 0, original.length, opts);
            Bitmap out = src;
            if (opts.outWidth > 0 && opts.outHeight > 0
                    && (src.getWidth() != opts.outWidth || src.getHeight() != opts.outHeight)) {
                out = Bitmap.createScaledBitmap(src, opts.outWidth, opts.outHeight, true);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Bitmap.CompressFormat fmt = entryName.endsWith(".webp")
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.PNG;
            out.compress(fmt, 90, buf);
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static long crc(byte[] data) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(data);
        return c.getValue();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    public static void writeFile(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
