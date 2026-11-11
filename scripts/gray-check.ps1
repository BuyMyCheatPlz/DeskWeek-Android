param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Device = "emulator-5554",
    [ValidateSet('debug','release')][string]$Variant = 'debug',
    [string]$EvidenceDirectory,
    [switch]$SkipBuild,
    [switch]$CleanInstall
)
# Debug smoke test by default; no release publication, adb root, or implicit uninstall.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $SdkRoot) { throw 'Specify -SdkRoot or ANDROID_HOME' }
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
$package = if ($Variant -eq 'debug') { 'com.deskweek.android.debug' } else { 'com.deskweek.android' }
$variantTask = (Get-Culture).TextInfo.ToTitleCase($Variant)
$apk = Join-Path $repo "app\build\outputs\apk\$Variant\app-$Variant.apk"
$passed = 0; $failed = 0; $skipped = 0
function Check([string]$Name, [scriptblock]$Action) {
    try {
        $result = & $Action
        if ($result -is [string] -and $result -eq 'SKIP') {
            $script:skipped++; Write-Host "SKIP $Name"
        } elseif ($result -is [bool] -and $result) {
            $script:passed++; Write-Host "PASS $Name"
        } else { $script:failed++; Write-Host "FAIL $Name : $result" }
    } catch { $script:failed++; Write-Host "FAIL $Name : $_" }
}
Push-Location $repo
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat ':app:lintDebug' ':app:testDebugUnitTest' ":app:assemble$variantTask" '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw 'Build/check failed' }
    }
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
    $deviceState = (& $adb -s $Device get-state 2>&1) -join ''
    if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne 'device') { throw "Device unavailable: $Device" }
    if ($CleanInstall) {
        & $adb -s $Device uninstall $package
        if ($LASTEXITCODE -ne 0) { throw 'Explicit clean uninstall failed' }
    }
    & $adb -s $Device install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Install failed; existing data was not removed' }
    & $adb -s $Device shell am force-stop $package
    & $adb -s $Device logcat -c
    & $adb -s $Device shell am start -W -n "$package/com.deskweek.android.MainActivity"
    if ($LASTEXITCODE -ne 0) { throw 'Activity start failed' }
    Start-Sleep -Seconds 3
    Check 'MainActivity resumed' {
        $activity = (& $adb -s $Device shell dumpsys activity activities) -join "`n"
        [bool]($activity -match ('topResumedActivity=.*' + [regex]::Escape($package) + '/com.deskweek.android.MainActivity'))
    }
    Check 'No app crash' {
        $crashes = (& $adb -s $Device logcat -d -b crash) -join "`n"
        [bool]($crashes -notmatch [regex]::Escape($package))
    }
    Check 'Widget provider registered' {
        $providers = (& $adb -s $Device shell dumpsys appwidget) -join "`n"
        [bool]($providers -match ([regex]::Escape($package) + '/com.deskweek.android.widget.DeskWeekWidgetReceiver'))
    }
    Check 'Existing task data is readable JSON' {
        if ($Variant -ne 'debug') { return 'SKIP' }
        $text = (& $adb -s $Device shell run-as $package cat files/data/data.json 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { return 'SKIP' }
        $data = $text | ConvertFrom-Json
        [bool]($null -ne $data.items)
    }
    Check 'Screenshot artifact' {
        if (-not $EvidenceDirectory) { return 'SKIP' }
        New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
        $screenshot = Join-Path $EvidenceDirectory "smoke-$Variant.png"
        & $adb -s $Device shell screencap -p /sdcard/deskweek-smoke.png | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Screenshot failed' }
        & $adb -s $Device pull /sdcard/deskweek-smoke.png $screenshot | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Screenshot pull failed' }
        & $adb -s $Device shell rm /sdcard/deskweek-smoke.png | Out-Null
        [bool]((Get-Item -LiteralPath $screenshot).Length -gt 8)
    }
    Write-Host "Smoke: $passed passed / $failed failed / $skipped skipped. This is not release approval."
    if ($failed -gt 0) { exit 1 }
} finally { Pop-Location }
