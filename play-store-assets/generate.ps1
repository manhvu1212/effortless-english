# Generates Play Store assets (app icon + feature graphic) from the adaptive
# launcher icon design. Run from repo root or this folder:
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File generate.ps1 [-Title "..."] [-Tagline "..."]

param(
    [string]$Title = 'Effortless English',
    [string]$Tagline = 'Nghe sâu, nói tự nhiên — mỗi ngày một chút.'
)

Add-Type -AssemblyName System.Drawing

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$icon = Join-Path $here 'ic_launcher_512.png'
$feat = Join-Path $here 'feature_graphic.png'

# Palette (matches values/colors.xml and ui/theme/Theme.kt)
$brand = [System.Drawing.Color]::FromArgb(0x2E, 0x6B, 0xD9)
$deep  = [System.Drawing.Color]::FromArgb(0x1D, 0x4F, 0xB0)
$white = [System.Drawing.Color]::FromArgb(0xFF, 0xFF, 0xFF)
$soft  = [System.Drawing.Color]::FromArgb(0xD8, 0xE4, 0xFA)

# The "E" of drawable/ic_launcher_foreground.xml, in its 108-unit viewport:
# M40,34 h28 v6 h-21 v11 h18 v6 h-18 v11 h21 v6 h-28 z
function Draw-Glyph {
    param([System.Drawing.Graphics]$g, [single]$ox, [single]$oy, [single]$s)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $u = $s / 108.0
    $b = New-Object System.Drawing.SolidBrush $white
    foreach ($r in @(@(40, 34, 28, 6), @(40, 34, 7, 40), @(47, 51, 18, 6), @(40, 68, 28, 6))) {
        $g.FillRectangle($b, [single]($ox + $r[0] * $u), [single]($oy + $r[1] * $u), [single]($r[2] * $u), [single]($r[3] * $u))
    }
    $b.Dispose()
}

# --- App icon (512x512, full bleed: Play applies its own mask) ---
$bmp = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear($brand)
Draw-Glyph -g $g -ox 0 -oy 0 -s 512
$g.Dispose()
$bmp.Save($icon, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "wrote $icon ($([math]::Round((Get-Item $icon).Length/1KB,1)) KB)"

# --- Feature graphic (1024x500) ---
$bmp = New-Object System.Drawing.Bitmap 1024, 500
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$rect = New-Object System.Drawing.Rectangle 0, 0, 1024, 500
$gradient = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $brand, $deep, 20.0
$g.FillRectangle($gradient, $rect)
$gradient.Dispose()

# Sound-wave arcs behind the glyph: listening is what the app is about.
$wave = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(40, 255, 255, 255)), 10
foreach ($r in 150, 210, 270) {
    $g.DrawArc($wave, [single](250 - $r), [single](250 - $r), [single](2 * $r), [single](2 * $r), -50, 100)
}
$wave.Dispose()

Draw-Glyph -g $g -ox 30 -oy 60 -s 380

$titleFont   = New-Object System.Drawing.Font 'Segoe UI', 64, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$taglineFont = New-Object System.Drawing.Font 'Segoe UI', 32, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$titleBrush   = New-Object System.Drawing.SolidBrush $white
$taglineBrush = New-Object System.Drawing.SolidBrush $soft
# Title and tagline stacked and centred vertically, whatever their length.
$textX = 440; $textW = 550; $gap = 18
$titleSize   = $g.MeasureString($Title, $titleFont, $textW)
$taglineSize = $g.MeasureString($Tagline, $taglineFont, $textW)
$top = (500 - ($titleSize.Height + $gap + $taglineSize.Height)) / 2
$g.DrawString($Title, $titleFont, $titleBrush, (New-Object System.Drawing.RectangleF $textX, $top, $textW, $titleSize.Height))
$g.DrawString($Tagline, $taglineFont, $taglineBrush, (New-Object System.Drawing.RectangleF $textX, ($top + $titleSize.Height + $gap), $textW, $taglineSize.Height))
$titleFont.Dispose(); $taglineFont.Dispose(); $titleBrush.Dispose(); $taglineBrush.Dispose()
$g.Dispose()
$bmp.Save($feat, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "wrote $feat ($([math]::Round((Get-Item $feat).Length/1KB,1)) KB)"
