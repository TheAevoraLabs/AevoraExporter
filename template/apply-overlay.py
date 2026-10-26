#!/usr/bin/env python3
"""Aevora template overlay applier.

Usage: apply-overlay.py <upstream-copy-dir> <overlay-dir>

Design: upstream is NEVER edited in place. This script copies the overlay
into a fresh copy of upstream and makes the smallest possible guarded
changes, so a new upstream commit overlays cleanly: if any anchor string
is missing, it fails loudly instead of silently producing a wrong build.

Changes:
  1. Add TemplateBoot.java (new file, no upstream conflict).
  2. MainActivity.onCreate: skip showLauncherUi() + update check in
     template mode; boot the bundled mod after prepareSwordigo().
  3. MainActivity.stopGame(): finish() in template mode, else show UI.
  4. AndroidManifest: literal label "Aevora Template" (patchable in the
     binary manifest by the exporter, without touching resources.arsc).
  5. app/build.gradle: applicationId net.aevora.template (namespace
     stays net.kiwi.launcher, so JNI/class names do not change),
     release builds disable shrinking so icon entry names stay readable.
  6. app/build.gradle: packaging.jniLibs.useLegacyPackaging = true.
     FIX 2026-10-26 (install failure): with the default uncompressed
     native libs, extractNativeLibs=false requires every .so in the APK
     to be page-aligned. Gradle zipaligns the template, but the
     on-device exporter patcher writes .so entries with no alignment
     pass, so its output could fail install with "App not installed".
     Legacy packaging compresses the .so files and makes the installer
     extract them, so APK alignment can never block install again.
     AGP sets extractNativeLibs=true for legacy packaging.
"""
import shutil
import sys
from pathlib import Path

src = Path(sys.argv[1])
overlay = Path(sys.argv[2])

def replace(path: Path, old: str, new: str, name: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"OVERLAY FAIL [{name}]: anchor not found in {path}\n--- expected ---\n{old}")
    if text.count(old) != 1:
        raise SystemExit(f"OVERLAY FAIL [{name}]: anchor found {text.count(old)}x in {path}, expected 1")
    path.write_text(text.replace(old, new))
    print(f"overlay: {name} -> {path.relative_to(src)}")

# 1. New file
shutil.copy2(overlay / "TemplateBoot.java",
             src / "app/src/main/java/net/kiwi/launcher/TemplateBoot.java")
print("overlay: TemplateBoot.java added")

# 2+3. MainActivity
main = src / "app/src/main/java/net/kiwi/launcher/MainActivity.java"
replace(main,
        "\t\t// UI, Previous Crash\n\t\tshowLauncherUi();\n\t\tcheckPreviousCrash();\n\n\t\tprepareSwordigo();\n\n\t\t// Launcher updates\n\t\tcheckForUpdate();",
        "\t\t// UI, Previous Crash\n"
        "\t\t// Aevora template overlay: single-mod mode skips the launcher UI.\n"
        "\t\t// No bundled mod -> behave exactly like the normal launcher.\n"
        "\t\tboolean templateMode = TemplateBoot.isTemplateMode(this);\n"
        "\t\tif (!templateMode) showLauncherUi();\n"
        "\t\tcheckPreviousCrash();\n\n"
        "\t\tprepareSwordigo();\n\n"
        "\t\tif (templateMode) {\n"
        "\t\t\t// Boot the bundled mod straight away. Any failure falls back\n"
        "\t\t\t// to the normal launcher UI instead of a black screen.\n"
        "\t\t\t// That includes vanilla Swordigo missing: launch() alone\n"
        "\t\t\t// would only Toast and leave a black screen, so check\n"
        "\t\t\t// swordigoReady/targetApkPath here first.\n"
        "\t\t\tModManager.ModInfo bundled = TemplateBoot.installBundledMod(this);\n"
        "\t\t\tif (bundled != null && swordigoReady && targetApkPath != null) {\n"
        "\t\t\t\tLauncher.currentMod = bundled;\n"
        "\t\t\t\tlaunch();\n"
        "\t\t\t} else {\n"
        "\t\t\t\tshowLauncherUi();\n"
        "\t\t\t}\n"
        "\t\t} else {\n"
        "\t\t\t// Launcher updates\n"
        "\t\t\tcheckForUpdate();\n"
        "\t\t}",
        "MainActivity.onCreate")
replace(main,
        "\t\tshowLauncherUi(); // show the launcher ui again",
        "\t\t// Aevora template overlay: no launcher UI to return to, exit.\n"
        "\t\tif (TemplateBoot.isTemplateMode(this)) {\n"
        "\t\t\tfinish();\n"
        "\t\t} else {\n"
        "\t\t\tshowLauncherUi(); // show the launcher ui again\n"
        "\t\t}",
        "MainActivity.stopGame")

# 4. Manifest literal label
manifest = src / "app/src/main/AndroidManifest.xml"
replace(manifest, 'android:label="@string/app_name"',
        'android:label="Aevora Template"', "Manifest label")

# 5b. settings.gradle: sandbox Maven relay + drop foojay resolver.
# Upstream settings.gradle points at google()/mavenCentral()/gradlePluginPortal()
# and the foojay toolchain plugin, which Java downloaders cannot reach
# through the sandbox egress proxy. launcher-test proved the relay at
# 127.0.0.1:8899 works; the JDK is already pinned via gradle.properties,
# so foojay is unnecessary. This is a sandbox-build overlay only: it does
# not change any app code, and a normal (non-sandbox) build can keep the
# upstream settings file.
(src / "settings.gradle").write_text("""pluginManagement {
    repositories {
        maven { url 'http://127.0.0.1:8899/google'; allowInsecureProtocol = true }
        maven { url 'http://127.0.0.1:8899/central'; allowInsecureProtocol = true }
        maven { url 'http://127.0.0.1:8899/portal'; allowInsecureProtocol = true }
    }
}
plugins {
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url 'http://127.0.0.1:8899/google'; allowInsecureProtocol = true }
        maven { url 'http://127.0.0.1:8899/central'; allowInsecureProtocol = true }
    }
}

rootProject.name = "Launcher"
include ':app'
""")
print("overlay: settings.gradle -> sandbox relay, foojay dropped")

# 5. build.gradle
gradle = src / "app/build.gradle"
replace(gradle, 'applicationId "net.kiwi.launcher"',
        'applicationId "net.aevora.template"', "applicationId")
replace(gradle,
        "    buildTypes {\n        release {\n            debuggable false",
        "    buildTypes {\n        release {\n            debuggable false\n"
        "            minifyEnabled false\n"
        "            shrinkResources false",
        "release no-shrink")
# AGP 9: upstream release enables R8/resources via `optimization {
# enable true }`, which minifyEnabled/shrinkResources do NOT turn off.
# That shrank the release APK to 1.9 MB and obfuscated every res/
# entry name, which breaks the exporter's icon patching (it matches
# res/**/ic_launcher*.png/.webp by name). Turn optimization off too.
replace(gradle,
        "            optimization {\n"
        "                enable true\n"
        "            }",
        "            optimization {\n"
        "                enable false\n"
        "            }",
        "release no-optimization")
replace(gradle,
        "    compileOptions {",
        "    packaging {\n"
        "        jniLibs {\n"
        "            useLegacyPackaging true\n"
        "        }\n"
        "    }\n"
        "    compileOptions {",
        "legacy packaging (2026-10-26 install fix)")
print("overlay: all anchors applied OK")
