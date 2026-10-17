{
  description = "F1 schedule Android widget";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };

      buildTools = "35.0.0";
      sdkWith = extra: pkgs.androidenv.composeAndroidPackages ({
        platformVersions = [ "35" ];
        buildToolsVersions = [ buildTools ];
        includeNDK = false;
        includeEmulator = false;
        includeSystemImages = false;
      } // extra);

      shellFor = android: extra:
        let sdk = "${android.androidsdk}/libexec/android-sdk";
        in pkgs.mkShell ({
          packages = [ pkgs.jdk17 pkgs.gradle android.androidsdk ] ++ (extra.packages or [ ]);
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          # aapt2 from Maven is dynamically linked and won't run on NixOS; use the SDK's.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildTools}/aapt2";
        } // builtins.removeAttrs extra [ "packages" ]);

      emulatorSdk = sdkWith {
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      };

      # Boots a headless emulator (creating the AVD on first use) and waits for it.
      emu = pkgs.writeShellScriptBin "emu" ''
        set -eu
        mkdir -p "$ANDROID_AVD_HOME"
        if [ ! -d "$ANDROID_AVD_HOME/f1.avd" ]; then
          echo no | avdmanager create avd -n f1 -d pixel_6 \
            -k "system-images;android-35;google_apis;x86_64"
        fi
        emulator -avd f1 -no-window -no-audio -no-boot-anim -no-snapshot \
          -gpu swiftshader_indirect >"$ANDROID_USER_HOME/emulator.log" 2>&1 &
        adb wait-for-device
        until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
        echo "emulator ready"
      '';
    in
    {
      devShells.${system} = {
        # `nix develop`: build only.
        default = shellFor (sdkWith { }) { };

        # `nix develop .#emulator`: adds the emulator, an Android 15 image and the `emu` helper.
        emulator = shellFor emulatorSdk {
          packages = [ emu ];
          shellHook = ''
            export ANDROID_USER_HOME="$PWD/.android"
            export ANDROID_AVD_HOME="$PWD/.android/avd"
          '';
        };
      };
    };
}
