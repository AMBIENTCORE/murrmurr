param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Bash = "C:\Program Files\Git\bin\bash.exe",
    [switch]$PackageOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root "build\ffmpeg-decoder"
$ndk = Join-Path $AndroidSdk "ndk\28.2.13676358"
if (!(Test-Path "$ndk\prebuilt\windows-x86_64\bin\make.exe") -or !(Test-Path $Bash)) {
    throw "Install Android NDK 28.2.13676358 and Git for Windows before building."
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Get-Source([string]$Name, [string]$Url, [string]$Tag, [string]$Commit) {
    $destination = Join-Path $work $Name
    $fresh = !(Test-Path "$destination\.git")
    if ($fresh) {
        Invoke-Checked git @("-c", "core.autocrlf=false", "-c", "core.longpaths=true",
            "clone", "--sparse", "--depth", "1", "--branch", $Tag, $Url, $destination)
    }
    Invoke-Checked git @("-C", $destination, "config", "core.autocrlf", "false")
    Invoke-Checked git @("-C", $destination, "config", "core.longpaths", "true")
    if ($fresh -and $Name -eq "media") {
        Invoke-Checked git @("-C", $destination, "sparse-checkout", "set", "libraries/decoder_ffmpeg")
    } elseif ($fresh) {
        Invoke-Checked git @("-C", $destination, "sparse-checkout", "disable")
    }
    $actual = & git -C $destination rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $Commit) { throw "Unexpected $Name source revision: $actual" }
}

New-Item $work -ItemType Directory -Force | Out-Null
Get-Source "media" "https://github.com/androidx/media.git" "1.8.0" "b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9"
Get-Source "ffmpeg" "https://github.com/FFmpeg/FFmpeg.git" "n6.1.4" "34277e12e80031c7f89494ba543684bc1dd0be8f"
$patch = Join-Path $PSScriptRoot "media3-ffmpeg-truehd.patch"
& git -C "$work\media" apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -ne 0) { Invoke-Checked git @("-C", "$work\media", "apply", $patch) }

$nativeBuild = @'
set -euo pipefail
work="$(cygpath -u "$1")"
ndk="$(cygpath -u "$2")"
export PATH="$ndk/prebuilt/windows-x86_64/bin:$PATH"
toolchain="$(cygpath -m "$ndk")/toolchains/llvm/prebuilt/windows-x86_64/bin"
ffmpeg="$work/ffmpeg"
jni="$work/media/libraries/decoder_ffmpeg/src/main/jni"
jobs="$(nproc)"
if (( jobs > 8 )); then jobs=8; fi
decoders="aac,ac3,alac,amrnb,amrwb,dca,eac3,flac,mlp,mp3,opus,pcm_alaw,pcm_mulaw,truehd,vorbis"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case "$abi" in
        arm64-v8a) arch=aarch64; target=aarch64-linux-android30 ;;
        armeabi-v7a) arch=arm; target=armv7a-linux-androideabi30 ;;
        x86) arch=x86; target=i686-linux-android30 ;;
        x86_64) arch=x86_64; target=x86_64-linux-android30 ;;
    esac
    mkdir -p "$work/native/$abi" "$work/project/src/main/jniLibs/$abi"
    cd "$ffmpeg"
    if [[ -f ffbuild/config.mak ]]; then make distclean > /dev/null; fi
    echo "Configuring LGPL-only FFmpeg for $abi"
    ./configure \
        --target-os=android --arch="$arch" --enable-cross-compile \
        --cc="$toolchain/clang.exe" --cxx="$toolchain/clang++.exe" \
        --as="$toolchain/clang.exe" --ld="$toolchain/clang.exe" \
        --ar="$toolchain/llvm-ar.exe" --nm="$toolchain/llvm-nm.exe" \
        --ranlib="$toolchain/llvm-ranlib.exe" --strip="$toolchain/llvm-strip.exe" \
        --extra-cflags="--target=$target -fPIC" \
        --extra-ldflags="--target=$target" \
        --disable-autodetect --disable-everything --disable-programs --disable-doc \
        --disable-debug --disable-shared --enable-static --enable-pic \
        --disable-avdevice --disable-avformat --disable-avfilter --disable-swscale \
        --disable-postproc --disable-network --disable-symver --disable-x86asm \
        --disable-gpl --disable-nonfree --enable-swresample --enable-decoder="$decoders" \
        > "$work/native/$abi/configure.log" 2>&1 || {
            tail -80 "$work/native/$abi/configure.log"
            if [[ -f ffbuild/config.log ]]; then tail -80 ffbuild/config.log; fi
            exit 1
        }
    grep -q '^#define CONFIG_GPL 0$' config.h
    grep -q '^#define CONFIG_NONFREE 0$' config.h
    cp config.h "$work/native/$abi/config.h"
    make -j"$jobs" > "$work/native/$abi/build.log" 2>&1 || { tail -80 "$work/native/$abi/build.log"; exit 1; }
    "$toolchain/clang++.exe" --target="$target" -std=c++11 -O2 -fPIC -shared \
        -static-libstdc++ -Wl,--no-undefined -Wl,-Bsymbolic -Wl,--exclude-libs,ALL \
        -Wl,-z,max-page-size=16384 -I"$ffmpeg" -I"$PWD" \
        "$jni/ffmpeg_jni.cc" -Wl,--start-group \
        libavcodec/libavcodec.a libswresample/libswresample.a libavutil/libavutil.a \
        -Wl,--end-group -llog -landroid -lm -lz \
        -o "$work/project/src/main/jniLibs/$abi/libffmpegJNI.so"
    "$toolchain/llvm-strip.exe" --strip-unneeded "$work/project/src/main/jniLibs/$abi/libffmpegJNI.so"
done
'@
[IO.File]::WriteAllText("$work\build-native.sh", $nativeBuild.Replace("`r`n", "`n"))
if (!$PackageOnly) { Invoke-Checked $Bash @("$work\build-native.sh", $work, $ndk) }
foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
    if (!(Test-Path "$work\project\src\main\jniLibs\$abi\libffmpegJNI.so") -or
        !(Select-String -Path "$work\native\$abi\config.h" -Pattern "^#define CONFIG_GPL 0$" -Quiet) -or
        !(Select-String -Path "$work\native\$abi\config.h" -Pattern "^#define CONFIG_NONFREE 0$" -Quiet)) {
        throw "Missing or non-LGPL native build for $abi"
    }
}

$project = Join-Path $work "project"
$settings = @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "media3-ffmpeg-decoder-lgpl"
'@
$build = @'
plugins { id("com.android.library") version "8.13.0" }
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets.named("main") {
        java.setSrcDirs(listOf("../media/libraries/decoder_ffmpeg/src/main/java"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    api("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.38.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:2.2.21")
}
'@
$rules = @'
-keepclasseswithmembernames,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** { native <methods>; }
# JNI resolves this callback and its parameter type by name.
-keepclassmembers,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
'@
[IO.File]::WriteAllText("$project\settings.gradle.kts", $settings)
[IO.File]::WriteAllText("$project\build.gradle.kts", $build)
[IO.File]::WriteAllText("$project\consumer-rules.pro", $rules)
[IO.File]::WriteAllText("$project\src\main\AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />')
Invoke-Checked "$root\gradlew.bat" @("--project-dir", $project, "bundleReleaseAar", "-Pandroid.useAndroidX=true", "--console=plain")

$destination = Join-Path $root "app\libs"
New-Item $destination -ItemType Directory -Force | Out-Null
Copy-Item "$project\build\outputs\aar\media3-ffmpeg-decoder-lgpl-release.aar" "$destination\media3-ffmpeg-decoder-1.8.0-lgpl.aar"
$licenses = Join-Path $root "app\src\main\assets\licenses"
New-Item $licenses -ItemType Directory -Force | Out-Null
Copy-Item "$work\ffmpeg\COPYING.LGPLv2.1" "$licenses\FFmpeg-LGPL-2.1.txt"
Copy-Item "$work\media\LICENSE" "$licenses\Media3-Apache-2.0.txt"
Invoke-Checked git @("-C", "$work\ffmpeg", "archive", "--format=tar.gz",
    "--output=$work\ffmpeg-6.1.4-source.tar.gz", "HEAD")
Invoke-Checked git @("-C", "$work\media", "archive", "--format=tar.gz",
    "--output=$work\media3-1.8.0-decoder-source.tar.gz", "HEAD", "libraries/decoder_ffmpeg", "LICENSE")
Compress-Archive -LiteralPath "$work\ffmpeg-6.1.4-source.tar.gz", "$work\media3-1.8.0-decoder-source.tar.gz",
    "$PSScriptRoot\build-ffmpeg-decoder.ps1", $patch -DestinationPath "$destination\ffmpeg-decoder-sources.zip" -Force
Get-FileHash "$destination\media3-ffmpeg-decoder-1.8.0-lgpl.aar" -Algorithm SHA256
