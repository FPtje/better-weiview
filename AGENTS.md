# Agent notes: tools available for REA

REA (Reverse Engineer Anything) is registered with Claude Code as the MCP server `rea`
(`npx -y rea-agents@6.4.0 mcp`). Its Android/APK analysis depends on the external tools
below. REA never installs them; each one is found through the path or environment
variable listed.

## Android / APK tools

| Tool | Path | How REA finds it |
| --- | --- | --- |
| JDK 21 (OpenJDK 21.0.12) | `/usr/lib/jvm/java-21-openjdk` | `JAVA_HOME` |
| JADX headless bridge 0.7.1 | `/home/falcop/tools/android/jadx-headless-mcp-0.7.1-all.jar` | `REA_JADX_MCP_JAR` |
| Apktool 3.0.3 (launcher) | `/home/falcop/.local/bin/apktool` (runs `/home/falcop/tools/android/apktool_3.0.3.jar`) | `REA_APKTOOL_COMMAND` |
| adb (platform-tools 37.0.1) | `/home/falcop/Android/Sdk/platform-tools/adb` (also `/usr/bin/adb`) | `REA_ADB_PATH`, otherwise `PATH` |
| Android SDK root | `/home/falcop/Android/Sdk` | `ANDROID_HOME` |
| Android platform | `/home/falcop/Android/Sdk/platforms/android-36` | SDK default |
| Build-tools 36.0.0 | `/home/falcop/Android/Sdk/build-tools/36.0.0` | SDK default |
| Command-line tools (sdkmanager, avdmanager) | `/home/falcop/Android/Sdk/cmdline-tools/latest/bin` | `PATH` |

Environment variables are set in `/home/falcop/.config/fish/config.fish`. They apply to new
shells only, so restart Claude Code from a fresh shell to pick them up.

### Capabilities and status

- **APK static analysis (`inspect_android_package`, class/method search, references):**
  uses JADX via `REA_JADX_MCP_JAR` and `JAVA_HOME`. Configured; not yet tested on a real APK.
- **Resource decoding (`decode_android_resources`):** uses Apktool via `REA_APKTOOL_COMMAND`.
  `inspect_apktool_client` reports it working. REA's docs verify Apktool 2.7.0 and newer;
  3.0.3 is untested with REA.
- **Device inspection and APK pulls (`list_adb_devices`, `pull_adb_package`, ...):** uses adb.
  No device or emulator is set up.

### Not installed

- Native binary analysis: Hopper, Ghidra (`GHIDRA_INSTALL_DIR`), and IDA (`REA_IDA_MCP_CONFIG`)
  are not configured.
- Other agent registrations (Claude Desktop, Codex, Cursor, etc.) were not set up.

## Notes

- Run `rea doctor --client claude_code` to check the Claude Code registration.
- `rea doctor` (full) also reports the skill as stale. The Claude Code skill is installed at
  `/home/falcop/.claude/skills/reverse-engineer-anything`. A refresh is pending:
  `bunx rea-agents@6.4.0 setup --client claude_code --skill-client claude_code --yes`.
