# Agent notes

The Nix flake (`flake.nix`) supplies all of the tools.
Direnv loads the flake (`.envrc`: `use flake`).
No tool is installed globally.

Start the agent in this directory with direnv loaded.
If the environment is not loaded, run commands through `nix develop --command`.

## Tasks

Use the `justfile` for the usual tasks.
Run `just` to show the full list.
Run `just check` after each change.
This recipe runs the Kotlin unit tests and `mypy --strict` on `tools/icap_client.py`.

## Tools from the flake

| Tool | Location |
| --- | --- |
| JDK | `JAVA_HOME` |
| Android SDK (read-only, in the Nix store) | `ANDROID_HOME` and `ANDROID_SDK_ROOT` |
| adb, build-tools | `PATH` |
| Ghidra | `GHIDRA_INSTALL_DIR`. For headless mode, use `$GHIDRA_INSTALL_DIR/support/analyzeHeadless`. |
| `just`, jadx, Apktool, Python 3, mypy, FFmpeg (`ffplay`) | `PATH` |

Nixpkgs does not supply Android platform 37.2.
Thus, the flake downloads it from Google directly.

The SDK is read-only, so Gradle cannot install SDK components.
To add an SDK component, change `flake.nix`.

CAUTION: Do not make a `local.properties` file with `sdk.dir`.
The Android Gradle plugin uses `sdk.dir` and ignores `ANDROID_HOME`.

## Reverse engineering

WARNING: The original Weiview APK is malware.
Do not use its code or libraries in this project.
Run it only in a disposable emulator.

- For static analysis of the APK, use jadx and Apktool.
- For native analysis, use Ghidra in headless mode.
- Adb is available.
  No device or emulator is set up.
