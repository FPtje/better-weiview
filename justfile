# Tasks for Better Weiview. Run them inside the Nix dev shell (direnv or `nix develop`).

app_id := "nl.falcop.betterweiview"

# Show the list of recipes.
default:
    @just --list

# Run the Kotlin unit tests.
test:
    ./gradlew testDebugUnitTest

# Type-check the laptop prototype.
typecheck:
    mypy --strict tools/icap_client.py

# Run all checks.
check: test typecheck

# Build the debug APK.
build:
    ./gradlew assembleDebug

# Build the debug APK and install it on the connected phone.
install:
    ./gradlew installDebug

# Build the signed release APK. This needs keystore.properties (see README).
release: _keystore
    ./gradlew assembleRelease

# Build the signed release APK and install it on the connected phone.
install-release: _keystore
    ./gradlew installRelease

# Install the debug build and start it on the connected phone.
run: install
    adb shell am start -n {{app_id}}/.MainActivity

# Show the log output of the app on the connected phone.
log:
    adb logcat -s BetterWeiview

# Record the camera stream from the laptop to an MJPEG file.
stream seconds="20" out="out.mjpeg" iface="wlan0":
    python3 tools/icap_client.py --iface '{{iface}}' --seconds '{{seconds}}' --out '{{out}}'

# Play an MJPEG file that `just stream` recorded.
play file="out.mjpeg":
    ffplay -f mjpeg '{{file}}'

# Delete the build outputs.
clean:
    ./gradlew clean

_keystore:
    @test -f keystore.properties || { echo "keystore.properties is missing (see README)" >&2; exit 1; }
