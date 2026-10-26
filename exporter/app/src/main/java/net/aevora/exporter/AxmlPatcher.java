package net.aevora.exporter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binary AndroidManifest.xml string-pool patcher, ported from
 * tools/patcher.py. Replaces exact strings; the caller builds the
 * replacement map so that every string equal to the old package, or
 * starting with old_package + ".", maps to the new package, plus
 * label and versionName by exact value.
 *
 * Supports UTF-16 (the encoding aapt2 uses for manifests here) and
 * UTF-8 string pools. Fails loudly if a style pool is present.
 */
public class AxmlPatcher {

    public static List<String> readStrings(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(0) != 0x0003) throw new IllegalArgumentException("not AXML");
        int poolOff = 8;
        if (b.getShort(poolOff) != 0x0001) throw new IllegalArgumentException("first chunk not string pool");
        int count = b.getInt(poolOff + 8);
        int flags = b.getInt(poolOff + 16);
        int stringsStart = b.getInt(poolOff + 20);
        boolean utf8 = (flags & 0x100) != 0;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = b.getInt(poolOff + 28 + i * 4);
            out.add(decode(data, poolOff + stringsStart + off, utf8));
        }
        return out;
    }

    private static String decode(byte[] data, int p, boolean utf8) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (utf8) {
            int b0 = data[p] & 0xFF;
            p += (b0 & 0x80) != 0 ? 2 : 1;
            int b1 = data[p] & 0xFF;
            int blen;
            if ((b1 & 0x80) != 0) { blen = ((b1 & 0x7F) << 8) | (data[p + 1] & 0xFF); p += 2; }
            else { blen = b1; p += 1; }
            return new String(data, p, blen, StandardCharsets.UTF_8);
        } else {
            int w0 = b.getShort(p) & 0xFFFF;
            int clen;
            if ((w0 & 0x8000) != 0) { clen = ((w0 & 0x7FFF) << 8) | (b.getShort(p + 2) & 0xFFFF); p += 4; }
            else { clen = w0; p += 2; }
            return new String(data, p, clen * 2, StandardCharsets.UTF_16LE);
        }
    }

    private static byte[] encode(String s, boolean utf8) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (utf8) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            for (int len : new int[]{s.length(), raw.length}) {
                if (len < 0x80) out.write(len);
                else { out.write(((len >> 8) | 0x80) & 0xFF); out.write(len & 0xFF); }
            }
            out.write(raw, 0, raw.length);
            out.write(0);
        } else {
            byte[] raw = s.getBytes(StandardCharsets.UTF_16LE);
            ByteBuffer h = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            if (s.length() < 0x8000) {
                out.write((byte) (s.length() & 0xFF)); out.write((byte) ((s.length() >> 8) & 0xFF));
            } else {
                h.putShort((short) (((s.length() >> 16) | 0x8000) & 0xFFFF));
                h.putShort((short) (s.length() & 0xFFFF));
                out.write(h.array(), 0, 4);
            }
            out.write(raw, 0, raw.length);
            out.write(0); out.write(0);
        }
        return out.toByteArray();
    }

    public static byte[] patch(byte[] data, Map<String, String> replacements) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int poolOff = 8;
        int poolSize = b.getInt(poolOff + 4);
        int count = b.getInt(poolOff + 8);
        int styleCount = b.getInt(poolOff + 12);
        int flags = b.getInt(poolOff + 16);
        if (styleCount != 0) throw new IllegalArgumentException("AXML style pool not handled");
        boolean utf8 = (flags & 0x100) != 0;
        List<String> strings = readStrings(data);
        List<byte[]> encoded = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int pos = 0;
        for (String s : strings) {
            String ns = replacements.getOrDefault(s, s);
            byte[] e = encode(ns, utf8);
            encoded.add(e);
            offsets.add(pos);
            pos += e.length;
        }
        int pad = (-pos) % 4;
        if (pad < 0) pad += 4;
        int newStringsStart = 28 + count * 4;
        int newPoolSize = newStringsStart + pos + pad;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(data, 0, 8);
        ByteBuffer h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) 0x0001); h.putShort((short) 28); h.putInt(newPoolSize);
        h.putInt(count); h.putInt(0); h.putInt(flags); h.putInt(newStringsStart); h.putInt(0);
        out.write(h.array(), 0, 28);
        ByteBuffer offs = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : offsets) offs.putInt(o);
        out.write(offs.array(), 0, count * 4);
        for (byte[] e : encoded) out.write(e, 0, e.length);
        for (int i = 0; i < pad; i++) out.write(0);
        out.write(data, poolOff + poolSize, data.length - (poolOff + poolSize));
        byte[] result = out.toByteArray();
        ByteBuffer r = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        r.putInt(4, result.length);
        return result;
    }

    /** Patch resources.arsc package-chunk name (256-byte UTF-16 field). */
    public static byte[] patchArscPackage(byte[] data, String oldPkg, String newPkg) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(0) != 0x0002) throw new IllegalArgumentException("not resources.arsc");
        int off = b.getShort(2) & 0xFFFF;
        byte[] out = data.clone();
        int patched = 0;
        while (off < data.length) {
            int t = b.getShort(off) & 0xFFFF;
            int sz = b.getInt(off + 4);
            if (sz <= 0) break;
            if (t == 0x0200) {
                String name = new String(data, off + 12, 256, StandardCharsets.UTF_16LE).split("\0")[0];
                if (name.equals(oldPkg)) {
                    byte[] enc = newPkg.getBytes(StandardCharsets.UTF_16LE);
                    if (enc.length > 256) throw new IllegalArgumentException("new package too long for ARSC");
                    System.arraycopy(enc, 0, out, off + 12, enc.length);
                    for (int i = enc.length; i < 256; i++) out[off + 12 + i] = 0;
                    patched++;
                }
            }
            off += sz;
        }
        if (patched == 0) throw new IllegalArgumentException("ARSC package chunk not found");
        return out;
    }
}
