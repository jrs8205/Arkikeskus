# Ottaa kuvakaappauksen laitteelta, vetaa koneelle ja pienentaa katselukokoon (<=540 px).
# Kaytto:  powershell -File tools\screenshot.ps1 [-Serial emulator-5554] [-Name lenkki]
param(
    [string]$Serial = "",
    [string]$Name = "ruutu"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$devArgs = @()
if ($Serial) { $devArgs = @("-s", $Serial) }

$outDir = "C:\Users\jrs82\Downloads\Samsung sm-t819\screenshot"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory $outDir | Out-Null }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $outDir "$Name-$stamp.png"

& $adb @devArgs shell screencap -p /sdcard/sc.png
& $adb @devArgs pull /sdcard/sc.png "$out" | Out-Null
& $adb @devArgs shell rm /sdcard/sc.png

# Pienennys Pillowilla (Read-katselu hylkaa >2000 px kuvat monikuvapyynnoissa)
python -c "from PIL import Image; im = Image.open(r'$out'); im.thumbnail((540, 1200)); im.save(r'$out')"
Write-Host "Tallennettu: $out"
