# Exporter app (Android)

App: `net.aevora.exporter`, v9 (versionName 9.0, versionCode 90,
matching the Kiwi template — see project README version rules),
minSdk 24, targetSdk 36 (sandbox; upstream template values are 37,
see project README for why the sandbox build uses 36).

Template APK is in `app/src/main/assets/template.apk` (copied from
`out/template-debug.apk`; the exporter always overwrites its
`assets/bundled_mod.zip`, so any placeholder bundled mod in the asset
never leaks into an export).

Flow: pick a mod zip (SAF) -> parse `properties.toml` -> validate the
mod id as an Android package (export is blocked, with a message, if it
is invalid — the sandbox Python patcher warns and proceeds, the app
does not ship a broken APK) -> patch template APK (package/label/
version/icon/bundled mod, via `AxmlPatcher` + `ApkPatcher`, the Java
port of `tools/patcher.py`) -> sign with apksig using a per-device
AndroidKeyStore key (`Signer.java`, no embedded private key) ->
install intent via FileProvider.

Limitations, stated plainly:
- No zipalign pass on device. That is safe now, not a gap: the template
  uses legacy packaging (compressed `.so`, extracted at install), and
  `ApkPatcher` preserves each entry's original compression method
  instead of forcing stored `.so`. The old code forced stored `.so`
  with no alignment pass, which failed install on
  `extractNativeLibs=false` templates — fixed 2026-10-26, do not
  reintroduce it.
- Signing, UI, and the full pick->export->install flow are built in the
  sandbox but NOT yet device-verified. Device test is the next step:
  install the exporter, pick a real mod zip, export, install the
  output, and check it boots straight into the game.
- The sandbox build uses compile/target SDK 36, not 37, because
  platform android-37 is not installed in the sandbox SDKs.
