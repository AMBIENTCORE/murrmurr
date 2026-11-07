param(
    [string]$Source = (Join-Path $PSScriptRoot '..\assets\logo.png'),
    [string]$Resources = (Join-Path $PSScriptRoot '..\app\src\main\res')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$image = [Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $Source).Path)
$mask = New-Object Drawing.Bitmap 96,96,([Drawing.Imaging.PixelFormat]::Format32bppArgb)
$attributes = New-Object Drawing.Imaging.ImageAttributes
$matrix = New-Object Drawing.Imaging.ColorMatrix
$matrix.Matrix00=0; $matrix.Matrix11=0; $matrix.Matrix22=0; $matrix.Matrix33=0
$matrix.Matrix40=1; $matrix.Matrix41=1; $matrix.Matrix42=1
$matrix.Matrix03=5/3; $matrix.Matrix13=5/3; $matrix.Matrix23=5/3; $matrix.Matrix43=-.5
$attributes.SetColorMatrix($matrix)
$markAttributes = New-Object Drawing.Imaging.ImageAttributes
$matrix.Matrix40=1; $matrix.Matrix41=.925; $matrix.Matrix42=.82
$markAttributes.SetColorMatrix($matrix)
function New-Logo([int]$Size, [double]$Fraction, [bool]$Monochrome, [bool]$Background, [string]$Path, [bool]$ForegroundOnly=$false) {
    $canvas = New-Object Drawing.Bitmap $Size,$Size,([Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($canvas)
    try {
        $graphics.Clear($(if ($Background) { [Drawing.Color]::FromArgb(255,9,7,10) } else { [Drawing.Color]::Transparent }))
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $edge = [int][Math]::Round($Size*$Fraction)
        $offset = [int](($Size-$edge)/2)
        $target = New-Object Drawing.Rectangle $offset,$offset,$edge,$edge
        if ($ForegroundOnly) {
            $graphics.DrawImage($image,$target,[single]$crop.X,[single]$crop.Y,[single]$crop.Width,[single]$crop.Height,[Drawing.GraphicsUnit]::Pixel,$markAttributes)
        } elseif ($Monochrome) {
            $graphics.DrawImage($image,$target,[single]$crop.X,[single]$crop.Y,[single]$crop.Width,[single]$crop.Height,[Drawing.GraphicsUnit]::Pixel,$attributes)
        } else {
            $graphics.DrawImage($image,$target,[single]$crop.X,[single]$crop.Y,[single]$crop.Width,[single]$crop.Height,[Drawing.GraphicsUnit]::Pixel)
        }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
        $canvas.Save($Path,[Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $canvas.Dispose() }
}
try {
    $graphics = [Drawing.Graphics]::FromImage($mask)
    try {
        $graphics.Clear([Drawing.Color]::Transparent)
        $graphics.DrawImage($image,(New-Object Drawing.Rectangle 0,0,96,96),0,0,$image.Width,$image.Height,[Drawing.GraphicsUnit]::Pixel,$attributes)
    } finally { $graphics.Dispose() }
    $left=96; $top=96; $right=-1; $bottom=-1
    for ($y=0; $y -lt 96; $y++) {
        for ($x=0; $x -lt 96; $x++) {
            if ($mask.GetPixel($x,$y).A -gt 32) {
                $left=[Math]::Min($left,$x); $right=[Math]::Max($right,$x)
                $top=[Math]::Min($top,$y); $bottom=[Math]::Max($bottom,$y)
            }
        }
    }
    if ($right -lt $left) { throw 'Logo has no visible foreground' }
    $width=($right-$left+1)*$image.Width/96
    $height=($bottom-$top+1)*$image.Height/96
    $side=[Math]::Min([Math]::Max($width,$height)*1.12,[Math]::Min($image.Width,$image.Height))
    $cx=($left+$right+1)*$image.Width/192
    $cy=($top+$bottom+1)*$image.Height/192
    $crop=New-Object Drawing.RectangleF ([single][Math]::Min([Math]::Max($cx-$side/2,0),$image.Width-$side)),([single][Math]::Min([Math]::Max($cy-$side/2,0),$image.Height-$side)),([single]$side),([single]$side)
    New-Logo 256 1 $false $false (Join-Path $Resources 'drawable-nodpi\murrmurr_logo.png') $true
    New-Logo 432 (64/108) $false $false (Join-Path $Resources 'drawable-nodpi\murrmurr_icon_foreground.png')
    New-Logo 96 .95 $true $false (Join-Path $Resources 'drawable-nodpi\ic_murrmurr_notification.png')
    foreach ($density in @(@('mdpi',48),@('hdpi',72),@('xhdpi',96),@('xxhdpi',144),@('xxxhdpi',192))) {
        New-Logo $density[1] .92 $false $true (Join-Path $Resources "mipmap-$($density[0])\ic_murrmurr.png")
    }
    'Generated launcher, transparent logo and monochrome notification assets; source logo unchanged.'
} finally { $markAttributes.Dispose(); $attributes.Dispose(); $mask.Dispose(); $image.Dispose() }
