# Aevora Exporter

Turn a Kiwi Launcher-format zip mod into a standalone APK.

## Name
Aevora Exporter. Not Evora. The exporter is an Android app. The output is a
single-mod game APK, not another launcher.

## Architecture (two pieces, one project)
1. **Template APK** (`template/`): a fork of lw-kizii/launcher, in single-mod
   mode. No picker UI. It boots straight into the one mod bundled with it.
2. **Exporter APK** (`exporter/`): an Android app that carries the template
   APK in its own assets folder. At runtime, on the phone, it edits that
   template APK from a user-picked mod zip and hands the result to the
   installer.

## How the template works
Upstream, in order: `MainActivity.onCreate` shows the launcher UI and runs
`prepareSwordigo()` (extracts `libswordigo.so` + `libopenal-soft.so` from the
installed vanilla `com.touchfoo.swordigo` APK), the user picks a mod
(`Launcher.currentMod`), then `startGame()` loads the game libs,
`loadHooks()` -> `ML_init()` -> `ML_load_mod_libraries()` dlopens
`<externalFiles>/mods/<id>/libraries/<abi>/` and calls `mod_init` if present.
Assets are a lookup order on open, not a copied VFS: `resources/` first,
then `mods/<id>/resources/`, then vanilla assets. Saves go to
`mods/<id>/saves/`.

Template change, kept deliberately tiny so upstream edits overlay cleanly:
- One new file: `TemplateBoot.java`. It checks for `assets/bundled_mod.zip`.
  If present, it extracts it (synchronously, hash-gated, preserving
  `saves/` and `data/`) to `mods/<id>/` and sets `Launcher.currentMod`.
  If absent, or if extraction fails, the APK behaves exactly like the
  normal launcher. No hard-coded mod id in code: the id is read from the
  zip's own `properties.toml`.
- One small patch to `MainActivity.java`: in template mode, skip
  `showLauncherUi()` and the launcher update check, boot the bundled mod
  after `prepareSwordigo()` succeeds, and finish the activity instead of
  showing the UI again on exit.
- Build overlay: `applicationId` becomes `net.aevora.template`
  (namespace stays `net.kiwi.launcher`, so Java class names and JNI names
  do not change), manifest label becomes the literal `Aevora Template`
  (so the exporter can patch it in the binary manifest without touching
  `resources.arsc`), release builds disable shrinking and AGP 9
  optimisation (minify/shrink alone do not turn optimisation off, and
  it obfuscates every `res/` entry name, which breaks icon replacement
  by name), and the template uses legacy packaging
  (`packaging.jniLibs.useLegacyPackaging`, so `extractNativeLibs=true`
  and the `.so` files are compressed and extracted at install).
  Legacy packaging is the 2026-10-26 install fix: with uncompressed
  native libs, Android's installer requires page-aligned `.so` files,
  and the on-device exporter patcher wrote them with no alignment pass,
  so its output failed install with a bare "App not installed".

Upstream is never edited in place. `template/build-template.sh` copies
upstream at a pinned commit (default `088e8d6`, Launcher 9.0, 2026-10-17)
to a build dir and applies the overlay there. Pulling a new upstream
commit is: change the pin, rebuild, re-run the patcher test.

Sandbox build notes (2026-10-26, verified by trial): Gradle daemon
dispatch fails without `-Djava.net.preferIPv4Stack=true` for both client
and daemon; the Maven relay at 127.0.0.1:8899
(`~/workspace/build/maven_relay.py`) must be running and
`settings.gradle` must point at it (upstream's foojay plugin is dropped,
JDK 25 is pinned via `gradle.properties` instead); AGP's preferred NDK
is 28.2.13676358, so `local.properties` points at the workspace SDK's
NDK 28.2 via a symlink in `~/android-sdk/ndk/` (r29 disagrees, CXX1104);
platform android-37 is not installed in either sandbox SDK, so the
sandbox build downgrades compile/target SDK 37 -> 36 only (upstream
values kept for a real build; the overlay uses no API-37 calls).

## How the exporter patches at runtime
Input: a launcher zip. Contract: root `properties.toml` with
`[mod] id/name/version/description`, `libraries/<abi>/*.so`,
`resources/`, optional `icon.png`.

Exporter steps, in order:
1. Parse `properties.toml` from the zip. The mod id becomes the output
   package name verbatim. No sanitising: if it is not a valid Android
   package, the install will fail, and the exporter must say so early.
2. Copy the template APK out of its assets.
3. Replace `assets/bundled_mod.zip` with the input zip.
4. Rewrite the binary `AndroidManifest.xml` string pool: package ->
   mod id, `"<old>.fileprovider"` -> `"<new>.fileprovider"`,
   label `Aevora Template` -> mod name, `versionName` -> mod version.
   `versionCode` is left at the template's value (a mod version string
   like `1.0.0` is not an int).
5. Replace icon entries (`ic_launcher*` / `ic_launcher_foreground`)
   with the zip's `icon.png`, converted to each entry's format and
   resized to that entry's dimensions. If the zip has no icon, keep
   the template icon.
6. Drop old signatures, zipalign, re-sign, fire the install intent
   (`REQUEST_INSTALL_PACKAGES`).

`tools/patcher.py` is the reference implementation of steps 2-6, used
in the sandbox to test against a built template APK. The exporter app
ports the same logic to Java (`exporter/app/.../Patcher.java`).

## Building from a fresh clone
Order matters, because the exporter app carries the template APK as an
asset, and that asset is a build artifact, not committed source:
1. Build the template: `template/build-template.sh [pin] [debug|release]
   [test-mod-zip]` — output lands in `out/`.
2. Copy it in: `cp out/template-debug.apk
   exporter/app/src/main/assets/template.apk`.
3. Build the exporter app with Gradle (`assembleDebug`).
4. Optionally, test the patcher in the sandbox:
   `tools/patcher.py --template out/template-debug.apk --mod
   tests/kiwiplus-with-icon.zip --out out/kiwiplus-exported.apk`.

What is NOT committed, and why: `out/` (built APKs and intermediates),
`template/build/`, `exporter/build/`, `exporter/.gradle/`,
`local.properties` (machine SDK paths), `*.keystore` (the sandbox test
key is test-only), and `exporter/app/src/main/assets/template.apk`
(see step 2 above).

Sandbox-only build files: the exporter `settings.gradle` and
`gradle.properties` committed here point at the sandbox Maven relay
(`127.0.0.1:8899`) and a pinned sandbox JDK, and the template build
downgrades SDK 37 -> 36 because platform android-37 is not installed
in the sandbox SDKs. On a normal machine, point `settings.gradle` at
`google()` / `mavenCentral()`, drop the JDK pin, and keep SDK 37.

## Licence
GPLv3 — see `LICENSE`. This project is GPLv3, like the Kiwi Launcher
template it builds on. That is also why copying from APK Explorer &
Editor (GPLv3) is clean here: credit + licence included, no licence
conflict.

## Targeted slice from APK Explorer & Editor (2026-10-26)
Pulled from https://github.com/apk-editor/APK-Explorer-Editor, copied
with credit, not blindly:
- **Copied, adapted:** `ZipAlign.java` (AEE, based on Iyxan23's
  zipalign-java) — now `exporter/.../ZipAlign.java`, package renamed,
  androidx annotations stripped (this app has no androidx dependency).
  It is wired deep into the flow: every export is now
  patch -> zipalign (4-byte, 16 KB for `.so`) -> sign, and the new
  "APK tools" card in the app lets any APK be stripped, zipaligned
  and re-signed on device.
- **Deliberately NOT copied:** AEE's `APKSigner` needs its `KeyPair`
  class, which needs sCommon's `FileUtils` — a whole extra library
  for a signer worse than the one already here. The exporter's own
  `Signer.java` (AndroidKeyStore key, no embedded private key) stays.
  AEE's AXML/ARSC editors need the external aXML library (JitPack,
  not fetchable through the sandbox Maven relay) and sCommon — that
  layer is the one hard lib, see below.
- **Hard lib, if wanted next:** aXML (`com.github.apk-editor:aXML`,
  JitPack). With its source/aar in hand, a full binary-XML editor
  becomes possible; without it, the string-pool patcher stays the
  right tool for package/label/version.

## Version rules (2026-10-26)
- **Exporter app: v9.** Its version always matches the Kiwi template
  in its assets (Launcher 9.0 / versionCode 90). When the template pin
  moves, bump the exporter version together with it.
- **Exported APKs: `<mod version>_kiwi9`.** The result carries the
  mod's own version, not Kiwi's, with the template major as a suffix
  — e.g. mod 1.4.5 on Kiwi 9 -> `1.4.5_kiwi9`. Already-suffixed
  versions are left alone; a missing mod version falls back to the
  template version. Implemented in both `tools/patcher.py` and the
  exporter's Java `ApkPatcher`.
- **Update check:** `_database/version.txt` in this repo holds only
  the latest major version number (`9`). The exporter fetches that
  file from `raw.githubusercontent.com` (same technique as Kiwi
  Launcher's `update.json` check) and reports only "update available"
  or "up to date" — no download link, the user updates manually.

## Exporter UI (v9, Material-style, framework-only)
Top bar (purple, v9 chip) + side panel (menu) + cards, no androidx
dependency so the sandbox build stays light:
- Import card: Pick mod zip, plus Shaders (experimental — tap shows
  "Shaders will be available soon!").
- Mod card (after a zip is picked): mod icon (auto-taken from the
  zip's `icon.png`, root wins, nested `*/icon.png` as fallback),
  name, result version, package, description.
- Actions (after a zip is picked / after export): Export, Install,
  Share, Update export (re-export).
- General: History, About, Check for updates.
- Side panel: Open RubyTouch (`in.aevora.ruby`, toast if not
  installed), Open Launcher (original Kiwi Launcher,
  `net.kiwi.launcher`), History, About, Check for updates, ReCaver
  (experimental — tap shows "ReCaver: Released soon!", a CaverEngine
  recomp reference), credits to Kizi (Kiwi Launcher template) and
  Quantum Creeper (exporter).
- History: every exported APK is recorded in
  `<externalFiles>/exported/history.json` and listed with Install /
  Share / Delete, any day.
- Template change in this round: in template mode only, the top-centre
  gear (settings / return-to-launcher) button is hidden, not removed
  — normal launcher mode still shows it, and `stopGame()` still
  finishes the activity.

## Limits, stated plainly
- V1 still needs vanilla Swordigo installed. The template gets its game
  libs and original assets from the vanilla APK, exactly like the
  launcher does. It is a single-mod shortcut APK, not a self-contained
  game APK. Bundling the game itself is a separate size/legal question.
- Changing `applicationId` changes the app's data folder, so each
  exported APK gets its own saves, not the launcher's saves.
- A mod that is arm64-only will still ship in a template that builds
  both ABIs; the template's own native libs cover both, the mod's
  libraries are loaded per-ABI at runtime as before.
