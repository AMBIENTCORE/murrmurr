param([string]$Ffmpeg = "ffmpeg")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root "app\src\androidTest\assets\audio"
New-Item $destination -ItemType Directory -Force | Out-Null
$fixtures = @(
    @("tone-aac.m4a", "aac"),
    @("tone-alac.m4a", "alac"),
    @("tone.mp3", "libmp3lame"),
    @("tone.flac", "flac"),
    @("tone.ogg", "libvorbis"),
    @("tone.opus", "libopus"),
    @("tone.wav", "pcm_s16le"),
    @("tone-alaw.wav", "pcm_alaw"),
    @("tone-mulaw.wav", "pcm_mulaw"),
    @("tone.ac3", "ac3"),
    @("tone.eac3", "eac3"),
    @("tone-dts.mka", "dca"),
    @("tone-truehd.mka", "truehd")
)
foreach ($fixture in $fixtures) {
    & $Ffmpeg -hide_banner -loglevel error -y -f lavfi `
        -i "sine=frequency=997:sample_rate=48000:duration=4" -ac 2 `
        -c:a $fixture[1] -strict -2 (Join-Path $destination $fixture[0])
    if ($LASTEXITCODE -ne 0) { throw "Failed to generate $($fixture[0])" }
}
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi `
    -i "color=c=black:s=16x16:r=1:d=4" -an -c:v mpeg4 (Join-Path $destination "no-audio.mp4")
if ($LASTEXITCODE -ne 0) { throw "Failed to generate the no-audio regression fixture" }
