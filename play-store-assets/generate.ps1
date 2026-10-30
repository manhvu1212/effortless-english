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

$wave  = [System.Drawing.Color]::FromArgb(0x9F, 0xC2, 0xFF)
$light = [System.Drawing.Color]::FromArgb(0x3B, 0x7D, 0xEB)

# The mark of drawable/ic_launcher_foreground.xml, in its 108-unit viewport: the "E"
# (M64,35 H40 V73 H64 M40,54 H56, stroke 7.5, round) and two sound waves centred on
# (57,54), radius 9 and 17, about +-45 degrees, stroke 5.
function Draw-Glyph {
    param([System.Drawing.Graphics]$g, [single]$ox, [single]$oy, [single]$s)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $u = $s / 108.0
    function P([single]$x, [single]$y) { New-Object System.Drawing.PointF ([single]($ox + $x * $u)), ([single]($oy + $y * $u)) }

    $pen = New-Object System.Drawing.Pen $white, ([single](7.5 * $u))
    $pen.StartCap = $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $g.DrawLines($pen, [System.Drawing.PointF[]]@((P 64 35), (P 40 35), (P 40 73), (P 64 73)))
    $g.DrawLine($pen, (P 40 54), (P 56 54))
    $pen.Dispose()

    $pen = New-Object System.Drawing.Pen $wave, ([single](5 * $u))
    $pen.StartCap = $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    foreach ($a in @(@(9, 46), @(17, 45))) {
        $r = $a[0]
        $g.DrawArc($pen, [single]($ox + (57 - $r) * $u), [single]($oy + (54 - $r) * $u), [single](2 * $r * $u), [single](2 * $r * $u), [single](-$a[1]), [single](2 * $a[1]))
    }
    $pen.Dispose()
}

# --- App icon (512x512, full bleed: Play applies its own mask) ---
$bmp = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($bmp)
$rect = New-Object System.Drawing.Rectangle 0, 0, 512, 512
$gradient = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $light, $deep, 45.0
$g.FillRectangle($gradient, $rect)
$gradient.Dispose()
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
$gradient = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $light, $deep, 20.0
$g.FillRectangle($gradient, $rect)
$gradient.Dispose()

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
