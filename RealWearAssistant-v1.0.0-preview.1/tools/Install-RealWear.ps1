param([string]$Adb = "adb", [string]$Serial = "", [string]$ApkPath = "")
$ErrorActionPreference = "Stop"
$packageRoot = Split-Path $PSScriptRoot -Parent
if (-not $ApkPath) { $ApkPath = Join-Path $packageRoot "app\build\outputs\apk\debug\app-debug.apk" }
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "APK absent. Compile assembleDebug ou indique le chemin du fichier telecharge avec -ApkPath." }
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) { $Adb = $sdkAdb } else { throw "ADB introuvable. Installe Android Platform Tools et ajoute ce dossier au PATH." }
}
$deviceArgs = @()
if ($Serial) { $deviceArgs = @("-s", $Serial) }
& $Adb @deviceArgs devices
& $Adb @deviceArgs install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw "Installation échouée. Vérifie l’autorisation USB. Si une ancienne version a une autre signature, consulte le README avant de la désinstaller." }
& $Adb @deviceArgs shell am start -n com.fred.realweargpt/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "APK installé, mais lancement impossible. Ouvre RealWear Assistant depuis le casque." }
Write-Host "Application installée. Pour la clé : python tools\import_key.py"
