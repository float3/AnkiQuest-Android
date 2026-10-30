{
  description = "AnkiQuest for Android, a fork of AnkiDroid";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = {nixpkgs, ...}: let
    lib = nixpkgs.lib;
    systems = ["x86_64-linux" "aarch64-linux" "aarch64-darwin"];
    forAllSystems = f:
      lib.genAttrs systems (system:
        f (import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        }));
    # Keep in step with compileSdk/targetSdk in gradle/libs.versions.toml,
    # SDK_BUILD_TOOLS_VERSION in .github/workflows/tests_emulator.yml and the
    # build tools AGP defaults to (36.0.0 for AGP 9.4).
    buildTools = "37.0.0";
    sdkFor = pkgs:
      (pkgs.androidenv.composeAndroidPackages {
        platformVersions = ["36" "37"];
        buildToolsVersions = ["36.0.0" buildTools];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      }).androidsdk;
    # gradle/gradle-daemon-jvm.properties asks for exactly a JetBrains 21
    # runtime, and nixpkgs only builds the newest one, so take JetBrains' own.
    jbrBuild = "21.0.11-b1163.125";
    jbrDownloads = {
      x86_64-linux = {
        arch = "linux-x64";
        hash = "sha256-JV5HF1hj7XvhCNo+rjEz6PGnjv9H+TjUR6zsEaVVaxQ=";
      };
      aarch64-linux = {
        arch = "linux-aarch64";
        hash = "sha256-bC0WkbHeSFrc5aSKzlPMbTY46gNqfk8ASzdnksPzaMI=";
      };
      aarch64-darwin = {
        arch = "osx-aarch64";
        hash = "sha256-bBpsT5NDwIUmzHh/OGd7WpiSgbeb9e5emy//2iVdZEE=";
      };
    };
    jbrFor = pkgs: let
      download = jbrDownloads.${pkgs.stdenv.hostPlatform.system};
      version = lib.head (lib.splitString "-" jbrBuild);
      build = lib.last (lib.splitString "-" jbrBuild);
    in
      pkgs.stdenv.mkDerivation {
        pname = "jbrsdk";
        version = jbrBuild;
        src = pkgs.fetchurl {
          url = "https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-${version}-${download.arch}-${build}.tar.gz";
          inherit (download) hash;
        };
        nativeBuildInputs = lib.optional pkgs.stdenv.hostPlatform.isLinux pkgs.autoPatchelfHook;
        buildInputs = lib.optionals pkgs.stdenv.hostPlatform.isLinux (with pkgs; [
          stdenv.cc.cc.lib
          zlib
          alsa-lib
          freetype
          fontconfig
          libx11
          libxext
          libxi
          libxrender
          libxtst
          wayland
        ]);
        # Only the desktop and debugger libraries miss anything; Gradle needs none of them.
        autoPatchelfIgnoreMissingDeps = true;
        dontStrip = true;
        installPhase = ''
          runHook preInstall
          mkdir -p $out
          if [ -d Contents/Home ]; then cp -r Contents/Home/. $out/; else cp -r . $out/; fi
          runHook postInstall
        '';
      };
  in {
    formatter = forAllSystems (pkgs: pkgs.alejandra);

    devShells = forAllSystems (pkgs: let
      sdk = sdkFor pkgs;
      sdkRoot = "${sdk}/libexec/android-sdk";
      jdk = jbrFor pkgs;
    in {
      default = pkgs.mkShell {
        packages = [jdk sdk pkgs.nodejs_22];
        JAVA_HOME = "${jdk}";
        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        # Gradle may not download a JDK: the downloaded ones do not run on NixOS.
        GRADLE_OPTS = lib.concatStringsSep " " ([
            "-Dorg.gradle.java.installations.auto-download=false"
            "-Dorg.gradle.java.installations.paths=${jdk}"
          ]
          # The aapt2 AGP fetches from Maven is linked for a usual Linux and
          # fails on NixOS, so use the SDK's own.
          ++ lib.optional pkgs.stdenv.hostPlatform.isLinux
          "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildTools}/aapt2");
        # The Rust backend's test library expects libstdc++ and libgcc_s.
        LD_LIBRARY_PATH = lib.optionalString pkgs.stdenv.hostPlatform.isLinux (lib.makeLibraryPath [pkgs.stdenv.cc.cc.lib]);
      };
    });
  };
}
