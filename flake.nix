{
  description = "Better Weiview: Android build and reverse-engineering tooling";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # Platform 37.2 (compileSdk in app/build.gradle.kts) is newer than anything
      # nixpkgs' androidenv ships (37.1), so fetch it from Google directly.
      platform372 = pkgs.stdenvNoCC.mkDerivation {
        pname = "android-platform";
        version = "37.2";
        src = pkgs.fetchurl {
          url = "https://dl.google.com/android/repository/platform-37.2_r01.zip";
          sha1 = "4bec12a02162ff6852df50b5fcf8ee762b050966";
        };
        nativeBuildInputs = [ pkgs.unzip ];
        installPhase = ''
          mkdir -p $out
          cp -r . $out
          # The zip lacks the package.xml that sdkmanager would write; without it
          # AGP treats the platform as not installed and tries to download it.
          cat > $out/package.xml <<'EOF'
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns11="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><localPackage path="platforms;android-37.2" obsolete="false"><type-details xsi:type="ns11:platformDetailsType"><api-level>37.2</api-level><codename></codename><extension-level>24</extension-level><base-extension>true</base-extension><layoutlib api="15"/></type-details><revision><major>1</major></revision><display-name>Android SDK Platform 37.2</display-name></localPackage></ns2:repository>
          EOF
        '';
      };

      baseSdk = (pkgs.androidenv.composeAndroidPackages {
        platformToolsVersion = "37.0.1";
        buildToolsVersions = [ "37.0.0" ];
        platformVersions = [ "36" ];
        includeEmulator = false;
        includeSystemImages = false;
      }).androidsdk;

      # Read-only SDK root: everything from baseSdk plus platforms/android-37.2.
      sdkRoot = pkgs.runCommand "android-sdk-root" { } ''
        base=${baseSdk}/libexec/android-sdk
        mkdir -p $out/platforms
        for d in $base/*; do
          [ "$(basename $d)" = platforms ] || ln -s $d $out/
        done
        for p in $base/platforms/*; do ln -s $p $out/platforms/; done
        ln -s ${platform372} $out/platforms/android-37.2
      '';

      jdk = pkgs.jdk25;
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          jdk
          pkgs.just
          pkgs.jadx
          pkgs.apktool
          pkgs.ghidra
          pkgs.python3
          pkgs.mypy
          pkgs.ffmpeg # ffplay for the *.mjpeg dumps from tools/icap_client.py
        ];

        JAVA_HOME = "${jdk}/lib/openjdk";
        ANDROID_HOME = "${sdkRoot}";
        ANDROID_SDK_ROOT = "${sdkRoot}";
        GHIDRA_INSTALL_DIR = "${pkgs.ghidra}/lib/ghidra";

        shellHook = ''
          export PATH="${sdkRoot}/platform-tools:${sdkRoot}/build-tools/37.0.0:$PATH"
          # AGP prefers local.properties' sdk.dir over ANDROID_HOME, which would
          # bypass the pinned SDK above.
          if [ -f local.properties ] && ! grep -q "${sdkRoot}" local.properties; then
            echo "warning: local.properties sets sdk.dir and overrides the nix SDK; delete it." >&2
          fi
        '';
      };
    };
}
