# PXP2 Multi-Device Wireless Debugging Installer
# Installs and launches PXP2 across all connected Pixels, Samsung Galaxy Tab S11, Razer Edge, etc.
param(
    [switch]$Build = $false,
    [switch]$Launch = $true,
    [switch]$PushIsos = $false
)

$ErrorActionPreference = 'Continue'
$PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location -LiteralPath $PSScriptRoot

$adb = 'C:\Users\Ben\AppData\Local\Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
}
if (-not $adb) {
    Write-Error "adb.exe not found. Please install Android Platform Tools."
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "       PXP2 Multi-Device Wireless Installer               " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Step 1: Optional Build
$apk = Join-Path $PSScriptRoot "dist\PXP2-fresh.apk"
if ($Build -or (-not (Test-Path $apk))) {
    Write-Host "`n[*] Building fresh PXP2 APK..." -ForegroundColor Yellow
    & powershell.exe -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0 -or (-not (Test-Path $apk))) {
        Write-Error "Build failed!"
        exit $LASTEXITCODE
    }
}

# Step 2: Start adb server and scan mDNS
Write-Host "`n[*] Starting ADB server and discovering wireless devices via mDNS..." -ForegroundColor Yellow
& $adb start-server | Out-Null
Start-Sleep -Seconds 2

# Known endpoints / device presets
$knownEndpoints = @(
    "192.168.0.22:5555",   # Pixel 9 Pro Fold
    "192.168.0.51:43487",  # Pixel Fold TLS port
    "192.168.0.56:43575"   # Samsung Galaxy Tab S11 TLS port
)

foreach ($ep in $knownEndpoints) {
    & $adb connect $ep 2>&1 | Out-Null
}

# Parse mDNS services to find all advertising wireless debugging endpoints
$mdnsRaw = & $adb mdns services 2>&1
if ($mdnsRaw) {
    foreach ($line in $mdnsRaw) {
        if ($line -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+)') {
            $addr = $matches[1]
            Write-Host "  -> Discovered mDNS endpoint: $addr" -ForegroundColor Gray
            & $adb connect $addr 2>&1 | Out-Null
        }
    }
}

# Wait for connections to stabilize
Start-Sleep -Seconds 2

# Step 3: Enumerate all connected devices
$rawDevices = & $adb devices -l
$devices = @()
foreach ($line in $rawDevices) {
    if ($line -match '^([^\s]+)\s+device\b') {
        $serial = $matches[1]
        $model = "Android Device"
        if ($line -match 'model:([^\s]+)') { $model = $matches[1] }
        $devices += [PSCustomObject]@{ Serial = $serial; Model = $model }
    }
}

if ($devices.Count -eq 0) {
    Write-Host "`n[!] No devices connected over wireless debugging." -ForegroundColor Red
    Write-Host "    Make sure 'Wireless debugging' is toggled ON in Developer Options on your devices."
    Write-Host "    Current adb output:"
    $rawDevices | Write-Host -ForegroundColor DarkGray
    exit 1
}

# Deduplicate devices (e.g. if connected via mDNS service name and ip:port)
$uniqueDevices = @()
$seenModels = @{}
foreach ($dev in $devices) {
    $key = $dev.Model
    if (-not $seenModels.ContainsKey($key)) {
        $seenModels[$key] = $dev.Serial
        $uniqueDevices += $dev
    }
}

Write-Host "`n[+] Found $($uniqueDevices.Count) active device(s):" -ForegroundColor Green
foreach ($d in $uniqueDevices) {
    Write-Host "    * $($d.Model) (Serial: $($d.Serial))" -ForegroundColor Cyan
}

# Step 4: Install APK & configure each device
$isoSources = @(
    "G:\games\psp\Dragon Ball Z - Shin Budokai.iso",
    "G:\games\psp\Loco Roco.iso"
)

foreach ($dev in $uniqueDevices) {
    $s = $dev.Serial
    $m = $dev.Model
    Write-Host "`n----------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host "[*] Installing to $m ($s)..." -ForegroundColor White

    # Install APK
    $installRes = & $adb -s $s install -r $apk 2>&1
    if ($installRes -match "Success") {
        Write-Host "    [OK] APK successfully installed!" -ForegroundColor Green
    } else {
        Write-Host "    [!] Install output: $installRes" -ForegroundColor Yellow
    }

    # Grant storage and appops permissions
    & $adb -s $s shell "appops set org.pxp2.fresh MANAGE_EXTERNAL_STORAGE allow" 2>&1 | Out-Null
    & $adb -s $s shell "pm grant org.pxp2.fresh android.permission.READ_EXTERNAL_STORAGE" 2>&1 | Out-Null
    & $adb -s $s shell "pm grant org.pxp2.fresh android.permission.WRITE_EXTERNAL_STORAGE" 2>&1 | Out-Null

    # Ensure Memory Stick directory structure
    & $adb -s $s shell "mkdir -p /sdcard/PXP/ISO /sdcard/PXP/PSP/GAME /sdcard/PXP/PSP/SAVEDATA" 2>&1 | Out-Null

    # Reverse port 8080 so device can access local PC Content Server seamlessly
    & $adb -s $s reverse tcp:8080 tcp:8080 2>&1 | Out-Null

    # Push ISOs if requested and not already present
    if ($PushIsos) {
        $existingIsos = & $adb -s $s shell "ls /sdcard/PXP/ISO/*.iso 2>/dev/null"
        if (-not ($existingIsos -match '\.iso')) {
            Write-Host "    [*] Pushing test ISOs to $m..." -ForegroundColor Yellow
            foreach ($iso in $isoSources) {
                if (Test-Path $iso) {
                    Write-Host "        -> $(Split-Path $iso -Leaf)..." -ForegroundColor Gray
                    & $adb -s $s push $iso /sdcard/PXP/ISO/ | Out-Null
                }
            }
        }
    }

    # Launch PXP2
    if ($Launch) {
        Write-Host "    [*] Launching PXP2 on $m..." -ForegroundColor Gray
        & $adb -s $s shell "am force-stop org.pxp2.fresh; am start -n org.pxp2.fresh/.RecoveryActivity" 2>&1 | Out-Null
    }
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  All wireless devices successfully updated and deployed! " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
