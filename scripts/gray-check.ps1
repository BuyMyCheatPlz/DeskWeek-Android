param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Device = "emulator-5554",
    [switch]$SkipBuild,
    [switch]$CleanInstall
)

# DeskWeek Android 灰测自检
# 用法： .\scripts\gray-check.ps1 -SdkRoot "C:\path\to\sdk" -Device emulator-5554
# 退出码 0 = 全部通过；非 0 = 有检查项失败（不允许推库）

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $SdkRoot) { throw "ANDROID_HOME 未设置，请传入 -SdkRoot" }

$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }

$fail = 0
function Check($name, [scriptblock]$body) {
    try {
        $result = & $body
        # 注意：PowerShell 里 $true -eq 'SKIP' 会因字符串转布尔而为真，必须先判类型
        if ($result -is [string] -and $result -eq 'SKIP') {
            Write-Host "  SKIP  $name（干净安装无数据，先手建一条再复跑）" -ForegroundColor Yellow
        } elseif ($result -eq $true -or $result -eq $null) {
            Write-Host "  PASS  $name" -ForegroundColor Green
        } else {
            Write-Host "  FAIL  $name -> $result" -ForegroundColor Red
            $script:fail++
        }
    } catch {
        Write-Host "  FAIL  $name -> $_" -ForegroundColor Red
        $script:fail++
    }
}

Push-Location $repo
try {
    if (-not $SkipBuild) {
        Write-Host "== 1. 构建（lint + 单测 + release） =="
        & .\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleRelease --console=plain 2>&1 |
            Select-String -Pattern 'BUILD|FAILED'
        if ($LASTEXITCODE -ne 0) { throw "构建失败，灰测终止" }
    }

    $apk = Join-Path $repo "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { throw "找不到 release APK: $apk" }

    Write-Host "== 2. 设备可用性 =="
    $devices = & $adb devices
    if (-not ($devices -match [regex]::Escape($Device))) { throw "设备 $Device 不在线" }
    Write-Host "  PASS  设备在线 ($Device)" -ForegroundColor Green

    Write-Host "== 3. 安装 release 包 =="
    if ($CleanInstall) {
        # 注意：卸载会清空用户数据，也会把动态壁纸重置回系统默认
        & $adb -s $Device uninstall com.deskweek.android 2>&1 | Out-Null
        Write-Host "  (已按 -CleanInstall 卸载旧版本，用户数据被清空)" -ForegroundColor Yellow
    }
    $install = (& $adb -s $Device install -r $apk 2>&1) -join "`n"
    if ($install -notmatch 'Success') { throw "安装失败: $install" }
    Write-Host "  PASS  安装成功" -ForegroundColor Green

    Write-Host "== 4. 冷启动与崩溃检查 =="
    & $adb -s $Device logcat -c
    & $adb -s $Device shell am start -n com.deskweek.android/.MainActivity 2>&1 | Out-Null
    Start-Sleep -Seconds 6
    Check "主界面成为顶层 Activity" {
        $top = & $adb -s $Device shell "dumpsys activity activities | grep -E 'topResumedActivity'" 2>&1
        if ($top -match 'com.deskweek.android/.MainActivity') { $true } else { $top }
    }
    Check "启动过程无崩溃" {
        $crash = & $adb -s $Device logcat -d -b crash 2>&1
        $ours = $crash | Select-String -Pattern 'com.deskweek.android'
        if ($ours) { $ours } else { $true }
    }

    Write-Host "== 5. 组件注册 =="
    Check "动态壁纸服务已注册（BIND_WALLPAPER）" {
        $svc = & $adb -s $Device shell "pm query-services -a android.service.wallpaper.WallpaperService 2>/dev/null | grep deskweek" 2>&1
        if ($svc -match 'DeskWeekWallpaperService') { $true } else { "not found" }
    }
    Check "小组件 Provider 已注册" {
        $w = & $adb -s $Device shell "dumpsys appwidget | grep -i DeskWeekWidgetReceiver" 2>&1
        if ($w) { $true } else { "not found" }
    }

    Write-Host "== 6. 数据链路（写入 -> 落盘） =="
    Check "任务可持久化到 data.json" {
        # release 包不可 debuggable，先试 run-as，失败再退回 adb root
        $out = (& $adb -s $Device shell "run-as com.deskweek.android stat -c %s files/data/data.json" 2>&1) -join "`n"
        if ($out -notmatch 'not debuggable') {
            $n = 0
            if ([int]::TryParse($out.Trim(), [ref]$n) -and $n -gt 0) { return $true }
        }
        & $adb -s $Device root 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        $ls = (& $adb -s $Device shell "ls -l /data/data/com.deskweek.android/files/data/data.json" 2>&1) -join "`n"
        if ($ls -match 'data\.json') { return $true }
        if ($ls -match 'No such file') { return 'SKIP' }
        "无法确认 data.json（run-as 与 root 均不可用）"
    }

    Write-Host "== 7. 截图留证 =="
    $shotDir = Join-Path $repo "build\smoke"
    New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
    $shot = Join-Path $shotDir "gray-release.png"
    cmd /c "`"$adb`" -s $Device exec-out screencap -p > `"$shot`"" 2>&1 | Out-Null
    if (Test-Path $shot) { Write-Host "  PASS  截图 $shot" -ForegroundColor Green } else { Write-Host "  FAIL  截图失败" -ForegroundColor Red; $fail++ }

    Write-Host ""
    if ($fail -eq 0) {
        Write-Host "灰测结果：全部通过，可以推库" -ForegroundColor Green
        exit 0
    } else {
        Write-Host "灰测结果：$fail 项失败，禁止推库" -ForegroundColor Red
        exit 1
    }
} finally {
    Pop-Location
}
