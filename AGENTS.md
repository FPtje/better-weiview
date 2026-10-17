# Agent notes: tools available for REA

REA (Reverse Engineer Anything) is registered with Claude Code as the MCP server `rea`
(`bunx -y rea-agents@latest mcp`). All tooling comes from the Nix flake (`flake.nix`),
loaded by direnv (`.envrc`: `use flake`). Nothing is installed globally.

**Start Claude Code from inside this directory with direnv loaded**, so the MCP server
inherits the environment variables below. Check with `echo $REA_JADX_MCP_JAR`.

## Android / APK tools (all from the flake)

| Tool | Provided as |
| --- | --- |
| JDK 21 | `JAVA_HOME` |
| JADX headless bridge 0.7.1 (fetched, sha256-pinned) | `REA_JADX_MCP_JAR` |
| Apktool 3.0.3 | `REA_APKTOOL_COMMAND` |
| adb (platform-tools 37.0.1) | `REA_ADB_PATH`, also on `PATH` |
| Android SDK (platforms 36 and 37.2, build-tools 36.0.0), read-only in the Nix store | `ANDROID_HOME` / `ANDROID_SDK_ROOT` |
| Ghidra 12.1.4 | `GHIDRA_INSTALL_DIR` (headless: `$GHIDRA_INSTALL_DIR/support/analyzeHeadless`) |
| jadx 1.5.6 CLI, Python 3 + mypy, ffmpeg (`ffplay`), bun (for `bun`/`bunx`) | `PATH` |

Platform 37.2 is newer than nixpkgs ships, so the flake fetches it from Google directly.
The SDK is read-only: Gradle can't auto-install SDK components, so new ones must be added
to `flake.nix`. Don't create `local.properties` with `sdk.dir`; AGP would prefer it over
`ANDROID_HOME`.

### Status

- **APK static analysis, resource decoding:** JADX and Apktool work through REA.
- **Device inspection and APK pulls:** adb is available; no device or emulator is set up.
- **Native analysis:** Ghidra works headless only (an existing project is at
  `/home/falcop/tools/ghidra/work/proj`). Hopper and IDA are not configured.

## Notes

- Run `rea doctor --client claude_code` to check the Claude Code registration.
- `rea doctor` (full) reports the skill as stale. The Claude Code skill is installed at
  `/home/falcop/.claude/skills/reverse-engineer-anything`. A refresh is pending:
  `bunx rea-agents@6.4.0 setup --client claude_code --skill-client claude_code --yes`.
