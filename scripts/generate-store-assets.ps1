$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetDirectory = Join-Path $PSScriptRoot '..\store-assets'
New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null

function Draw-Logo($graphics, [float]$x, [float]$y, [float]$size) {
    $saved = $graphics.Save()
    $graphics.TranslateTransform($x, $y)
    $graphics.ScaleTransform($size / 108, $size / 108)
    $shield = New-Object System.Drawing.Drawing2D.GraphicsPath
    $shield.AddLines([System.Drawing.PointF[]]@([System.Drawing.PointF]::new(54,18), [System.Drawing.PointF]::new(26,30), [System.Drawing.PointF]::new(26,52)))
    $shield.AddBezier(26,52,26,70,37.5,85,54,90)
    $shield.AddBezier(54,90,70.5,85,82,70,82,52)
    $shield.AddLines([System.Drawing.PointF[]]@([System.Drawing.PointF]::new(82,52), [System.Drawing.PointF]::new(82,30), [System.Drawing.PointF]::new(54,18)))
    $shield.CloseFigure()
    $teal = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#00BFA5'))
    $graphics.FillPath($teal, $shield)
    $white = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 2)
    $white.StartCap = $white.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawEllipse($white,42,32,24,24)
    $graphics.DrawEllipse($white,48,32,12,24)
    $graphics.DrawLine($white,42,44,66,44)
    $white.Width = 3.5
    $graphics.DrawLines($white, [System.Drawing.PointF[]]@([System.Drawing.PointF]::new(45,65), [System.Drawing.PointF]::new(51,71), [System.Drawing.PointF]::new(64,58)))
    $white.Dispose(); $teal.Dispose(); $shield.Dispose()
    $graphics.Restore($saved)
}

function New-Canvas([int]$width, [int]$height) {
    $bitmap = [System.Drawing.Bitmap]::new($width,$height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#101114'))
    return @($bitmap,$graphics)
}

$icon = New-Canvas 512 512
Draw-Logo $icon[1] 6 6 500
$icon[0].Save((Join-Path $assetDirectory 'securevpn-play-icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$icon[1].Dispose(); $icon[0].Dispose()

$feature = New-Canvas 1024 500
Draw-Logo $feature[1] 635 52 390
$titleFont = [System.Drawing.Font]::new('Segoe UI',54,[System.Drawing.FontStyle]::Bold,[System.Drawing.GraphicsUnit]::Pixel)
$bodyFont = [System.Drawing.Font]::new('Segoe UI',25,[System.Drawing.FontStyle]::Regular,[System.Drawing.GraphicsUnit]::Pixel)
$whiteBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
$grayBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#BBC3C7'))
$feature[1].DrawString('SecureVPN',$titleFont,$whiteBrush,64,152)
$feature[1].DrawString("Your profiles. Your connection.",$bodyFont,$grayBrush,68,230)
$feature[1].DrawString('Import links, QR codes and subscriptions.',$bodyFont,$grayBrush,68,268)
$feature[0].Save((Join-Path $assetDirectory 'securevpn-feature-graphic.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$titleFont.Dispose(); $bodyFont.Dispose(); $whiteBrush.Dispose(); $grayBrush.Dispose()
$feature[1].Dispose(); $feature[0].Dispose()
Get-ChildItem -LiteralPath $assetDirectory -Filter '*.png' | Select-Object Name,Length
