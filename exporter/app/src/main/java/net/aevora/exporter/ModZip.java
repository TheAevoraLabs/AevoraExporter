package net.aevora.exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Parses a launcher mod zip: [mod] properties.toml + optional icon.png. */
public class ModZip {
    public String id, name, version, description;
    public byte[] zipBytes;
    public byte[] iconPng;

    public static ModZip parse(byte[] zipBytes) throws IOException {
        ModZip m = new ModZip();
        m.zipBytes = zipBytes;
        String props = null;
        byte[] nestedIcon = null;
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals("properties.toml")) {
                    props = new String(readAll(zin), java.nio.charset.StandardCharsets.UTF_8);
                } else if (e.getName().equals("icon.png")) {
                    // Root icon wins, exactly per the launcher contract.
                    m.iconPng = readAll(zin);
                } else if (nestedIcon == null
                        && e.getName().toLowerCase().endsWith("/icon.png")) {
                    // Fallback for zips that nest the icon one folder deep.
                    nestedIcon = readAll(zin);
                }
                zin.closeEntry();
            }
        }
        if (m.iconPng == null) m.iconPng = nestedIcon;
        if (props == null) throw new IOException("mod zip missing properties.toml");
        Map<String, String> map = new HashMap<>();
        boolean inMod = false;
        for (String line : props.split("\n")) {
            line = line.trim();
            if (line.startsWith("[")) { inMod = line.equals("[mod]"); continue; }
            if (!inMod || line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            map.put(k, v);
        }
        m.id = map.get("id");
        m.name = map.get("name");
        m.version = map.get("version");
        m.description = map.get("description");
        if (m.id == null || m.id.isEmpty()) throw new IOException("properties.toml missing [mod] id");
        if (m.name == null) m.name = m.id;
        return m;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
