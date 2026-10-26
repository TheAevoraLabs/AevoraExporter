#!/usr/bin/env python3
"""Aevora Exporter patcher — reference implementation.

Usage:
  patcher.py --template template.apk --mod mod.zip --out out.apk
             [--old-package net.aevora.template]
             [--old-label "Aevora Template"] [--old-version 9.0]
             [--keystore out/test.keystore] [--skip-sign]

What it does, in order:
  1. Parses properties.toml from the mod zip: [mod] id/name/version.
     The id becomes the output package name verbatim.
  2. Copies the template APK, replacing assets/bundled_mod.zip with the
     input mod zip.
  3. Rewrites the binary AndroidManifest.xml string pool: every string
     equal to the old package, or starting with old_package + ".",
     becomes the new package (this covers the package itself, the
     .fileprovider authority, .androidx-startup, and the
     DYNAMIC_RECEIVER permission, while leaving class names like
     net.kiwi.launcher.MainActivity untouched, because those are in the
     namespace, not the applicationId). Label and versionName strings
     are replaced by exact value.
  4. Patches resources.arsc: the package-chunk name field (256-byte
     UTF-16) from old package to new package. Verified on a real
     launcher APK: the old package occurs exactly once in resources.arsc
     (that field) and zero times in DEX, so no DEX patch is needed.
  5. Replaces icon entries (ic_launcher* / ic_launcher_foreground)
     with the mod's icon.png, converted to each entry's format and
     resized to that entry's dimensions. Missing icon -> keep template.
  6. Drops old signatures, zipaligns, re-signs with apksigner.

This is the sandbox reference. The exporter Android app ports the same
steps to Java; the AXML/ARSC formats used here are the ones to port.
"""
import argparse
import io
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- TOML

def parse_mod_props(mod_zip_bytes: bytes):
    with zipfile.ZipFile(io.BytesIO(mod_zip_bytes)) as z:
        try:
            text = z.read("properties.toml").decode("utf-8")
        except KeyError:
            raise SystemExit("mod zip missing properties.toml")
        icon = None
        try:
            icon = z.read("icon.png")
        except KeyError:
            pass
    props = {}
    in_mod = False
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            in_mod = (line == "[mod]")
            continue
        if not in_mod:
            continue
        m = re.match(r'(\w+)\s*=\s*"(.*)"\s*$', line) or re.match(r"(\w+)\s*=\s*'(.*)'\s*$", line)
        if m:
            props[m.group(1)] = m.group(2)
        else:
            m = re.match(r"(\w+)\s*=\s*(\S+)\s*$", line)
            if m:
                props[m.group(1)] = m.group(2)
    if not props.get("id"):
        raise SystemExit("properties.toml missing [mod] id")
    return props, icon

def valid_package(pkg: str) -> bool:
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+", pkg or ""):
        return False
    return True

# ---------------------------------------------------------------- AXML

def u16(d, o): return struct.unpack_from("<H", d, o)[0]
def u32(d, o): return struct.unpack_from("<I", d, o)[0]

def decode_string(data: bytes, p: int, utf8: bool) -> str:
    if utf8:
        b0 = data[p]
        if b0 & 0x80:
            p += 2
        else:
            p += 1
        b0 = data[p]
        if b0 & 0x80:
            blen = ((b0 & 0x7F) << 8) | data[p + 1]
            p += 2
        else:
            blen = b0
            p += 1
        return data[p:p + blen].decode("utf-8", "replace")
    else:
        w0 = struct.unpack_from("<H", data, p)[0]
        if w0 & 0x8000:
            clen = ((w0 & 0x7FFF) << 8) | struct.unpack_from("<H", data, p + 2)[0]
            p += 4
        else:
            clen = w0
            p += 2
        return data[p:p + clen * 2].decode("utf-16-le", "replace")

def encode_string(s: str, utf8: bool) -> bytes:
    if utf8:
        raw = s.encode("utf-8")
        out = bytearray()
        # char length, then byte length, each with 1- or 2-byte encoding
        for length in (len(s), len(raw)):
            if length < 0x80:
                out.append(length)
            else:
                out.append(((length >> 8) | 0x80) & 0xFF)
                out.append(length & 0xFF)
        out += raw
        out.append(0)
        return bytes(out)
    else:
        raw = s.encode("utf-16-le")
        out = bytearray()
        if len(s) < 0x8000:
            out += struct.pack("<H", len(s))
        else:
            out += struct.pack("<H", ((len(s) >> 16) | 0x8000) & 0xFFFF)
            out += struct.pack("<H", len(s) & 0xFFFF)
        out += raw
        out += b"\x00\x00"
        return bytes(out)

def patch_axml(data: bytes, replacements: dict) -> bytes:
    """Replacements: exact string -> new string. Also applied by the
    caller as a separate pass for package-prefix strings."""
    if u16(data, 0) != 0x0003:
        raise SystemExit("AndroidManifest.xml is not binary AXML")
    pool_off = 8
    if u16(data, pool_off) != 0x0001:
        raise SystemExit("AXML: first chunk is not a string pool")
    pool_size = u32(data, pool_off + 4)
    scount = u32(data, pool_off + 8)
    style_count = u32(data, pool_off + 12)
    flags = u32(data, pool_off + 16)
    strings_start = u32(data, pool_off + 20)
    utf8 = bool(flags & 0x100)
    offsets = [u32(data, pool_off + 28 + i * 4) for i in range(scount)]
    strings = []
    for i in range(scount):
        strings.append(decode_string(data, pool_off + strings_start + offsets[i], utf8))
    changed = 0
    new_strings = []
    for s in strings:
        if s in replacements:
            new_strings.append(replacements[s])
            changed += 1
        else:
            new_strings.append(s)
    if changed == 0:
        print("axml: no strings replaced (check old values)", file=sys.stderr)
    # Rebuild pool
    encoded = [encode_string(s, utf8) for s in new_strings]
    new_offsets = []
    pos = 0
    for e in encoded:
        new_offsets.append(pos)
        pos += len(e)
    # Pad pool data to 4 bytes (styles follow, or next chunk)
    pad = (-pos) % 4
    pool_data = b"".join(encoded) + b"\x00" * pad
    new_strings_start = 28 + scount * 4 + style_count * 4
    new_pool_size = new_strings_start + len(pool_data)
    # Style offsets are preserved (styles are empty in manifests we handle;
    # if styles existed, their offsets would need the same rebuild — fail loudly)
    if style_count != 0:
        raise SystemExit("AXML: style pool present, not handled")
    header = bytearray()
    header += struct.pack("<HHI", 0x0001, 28, new_pool_size)
    header += struct.pack("<IIIII", scount, style_count, flags, new_strings_start, 0)
    for o in new_offsets:
        header += struct.pack("<I", o)
    rest = data[pool_off + pool_size:]
    out = bytearray(data[:8])
    out += header + pool_data + rest
    # Fix total file size
    struct.pack_into("<I", out, 4, len(out))
    print(f"axml: replaced {changed} string(s), {len(data)} -> {len(out)} bytes")
    return bytes(out)

def manifest_strings(data: bytes):
    pool_off = 8
    scount = u32(data, pool_off + 8)
    flags = u32(data, pool_off + 16)
    strings_start = u32(data, pool_off + 20)
    utf8 = bool(flags & 0x100)
    offsets = [u32(data, pool_off + 28 + i * 4) for i in range(scount)]
    return [decode_string(data, pool_off + strings_start + offsets[i], utf8) for i in range(scount)]

# ---------------------------------------------------------------- ARSC

def patch_arsc(data: bytes, old_pkg: str, new_pkg: str) -> bytes:
    if u16(data, 0) != 0x0002:
        raise SystemExit("resources.arsc is not a resource table")
    off = u16(data, 2)
    out = bytearray(data)
    patched = 0
    while off < len(data):
        t = u16(data, off)
        sz = u32(data, off + 4)
        if sz <= 0:
            break
        if t == 0x0200:
            name_raw = data[off + 12:off + 12 + 256]
            name = name_raw.decode("utf-16-le", "replace").split("\x00")[0]
            if name == old_pkg:
                enc = new_pkg.encode("utf-16-le")
                if len(enc) > 256:
                    raise SystemExit("new package too long for ARSC name field")
                out[off + 12:off + 12 + 256] = enc + b"\x00" * (256 - len(enc))
                patched += 1
        off += sz
    print(f"arsc: patched {patched} package chunk(s)")
    return bytes(out)

# ---------------------------------------------------------------- icons

def convert_icon(icon_png: bytes, target_ext: str, width: int, height: int) -> bytes:
    from PIL import Image
    im = Image.open(io.BytesIO(icon_png)).convert("RGBA")
    if width and height and (im.width, im.height) != (width, height):
        im = im.resize((width, height), Image.LANCZOS)
    buf = io.BytesIO()
    if target_ext == ".webp":
        im.save(buf, format="WEBP", quality=90)
    else:
        im.save(buf, format="PNG")
    return buf.getvalue()

def icon_size(entry_bytes: bytes):
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(entry_bytes))
        return im.width, im.height
    except Exception:
        return 0, 0

# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", required=True)
    ap.add_argument("--mod", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--old-package", default="net.aevora.template")
    ap.add_argument("--old-label", default="Aevora Template")
    ap.add_argument("--old-version", default="9.0")
    ap.add_argument("--keystore", default="")
    ap.add_argument("--skip-sign", action="store_true")
    ap.add_argument("--build-tools", default=str(Path.home() / "android-sdk/build-tools/37.0.0"))
    args = ap.parse_args()

    mod_bytes = Path(args.mod).read_bytes()
    props, icon = parse_mod_props(mod_bytes)
    new_pkg = props["id"]
    new_name = props.get("name") or new_pkg
    new_version = props.get("version") or args.old_version
    if not valid_package(new_pkg):
        print(f"WARNING: mod id {new_pkg!r} is not a valid Android package; "
              "the output will likely fail to install. Using it verbatim per spec.",
              file=sys.stderr)
    print(f"mod: id={new_pkg} name={new_name} version={new_version} icon={'yes' if icon else 'no'}")

    with zipfile.ZipFile(args.template) as zin:
        entries = [(info, zin.read(info.filename)) for info in zin.infolist()
                   if not info.filename.startswith("META-INF/")]

    # Manifest + ARSC patches
    out_entries = []
    for info, data in entries:
        if info.filename == "AndroidManifest.xml":
            strings = manifest_strings(data)
            repl = {}
            for s in strings:
                if s == args.old_package or s.startswith(args.old_package + "."):
                    repl[s] = new_pkg + s[len(args.old_package):]
                if s == args.old_label:
                    repl[s] = new_name
                if s == args.old_version:
                    repl[s] = new_version
            print(f"manifest replacements: {repl}")
            data = patch_axml(data, repl)
        elif info.filename == "resources.arsc":
            data = patch_arsc(data, args.old_package, new_pkg)
        elif icon and (info.filename.startswith("res/") and
                       info.filename.endswith((".png", ".webp")) and
                       ("ic_launcher" in info.filename or "ic_launcher_foreground" in info.filename)):
            ext = ".webp" if info.filename.endswith(".webp") else ".png"
            w, h = icon_size(data)
            try:
                data = convert_icon(icon, ext, w, h)
                print(f"icon: replaced {info.filename} ({w}x{h} -> {ext})")
            except Exception as e:
                print(f"icon: skip {info.filename}: {e}", file=sys.stderr)
        out_entries.append((info, data))

    # Bundled mod: replace or add
    names = {info.filename for info, _ in out_entries}
    if "assets/bundled_mod.zip" in names:
        out_entries = [(info, mod_bytes if info.filename == "assets/bundled_mod.zip" else data)
                       for info, data in out_entries]
    else:
        info = zipfile.ZipInfo("assets/bundled_mod.zip")
        out_entries.append((info, mod_bytes))
    print("bundled_mod.zip: injected")

    tmp_apk = Path(args.out + ".unsigned")
    with zipfile.ZipFile(tmp_apk, "w") as zout:
        for info, data in out_entries:
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.external_attr = info.external_attr
            # Native libs must be stored, not deflated, for zipalign/page use
            ni.compress_type = zipfile.ZIP_STORED if info.filename.endswith(".so") else zipfile.ZIP_DEFLATED
            zout.writestr(ni, data)

    if args.skip_sign:
        tmp_apk.replace(args.out)
        print(f"wrote (unsigned): {args.out}")
        return

    bt = Path(args.build_tools)
    aligned = Path(args.out + ".aligned")
    subprocess.run([str(bt / "zipalign"), "-f", "4", str(tmp_apk), str(aligned)], check=True)
    keystore = args.keystore or str(Path(args.out).parent / "aevora-test.keystore")
    if not Path(keystore).exists():
        print(f"creating test keystore: {keystore}")
        subprocess.run([
            "keytool", "-genkeypair", "-v", "-keystore", keystore,
            "-alias", "aevora", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-storepass", "aevora123", "-keypass", "aevora123",
            "-dname", "CN=Aevora Labs, OU=Aevora, O=Aevora Labs, C=IN",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([
        str(bt / "apksigner"), "sign", "--ks", keystore,
        "--ks-pass", "pass:aevora123", "--key-pass", "pass:aevora123",
        "--out", args.out, str(aligned),
    ], check=True)
    print(f"wrote (signed): {args.out}")
    # Verify + badging
    subprocess.run([str(bt / "apksigner"), "verify", "--verbose", args.out], check=False)
    r = subprocess.run([str(bt / "aapt2"), "dump", "badging", args.out],
                       capture_output=True, text=True)
    for line in r.stdout.splitlines()[:3]:
        print("badging:", line)

if __name__ == "__main__":
    main()
