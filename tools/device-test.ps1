param(
    [string]$PhoneReference = '/sdcard/Dev/Luminescence.hnt'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
$reference = Join-Path ([System.IO.Path]::GetTempPath()) ("iconmanager-reference-" + [guid]::NewGuid() + ".hnt")
$testsInstalled = $false
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
try {
    Invoke-Checked '.\gradlew.bat' @('--no-daemon', '--console=plain', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
    Invoke-Checked 'adb' @('install', '-r', '.\app\build\outputs\apk\debug\app-debug.apk')
    Invoke-Checked 'adb' @('install', '-r', '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
    $testsInstalled = $true
    Invoke-Checked 'adb' @('pull', $PhoneReference, $reference)
    Invoke-Checked 'adb' @('shell', 'mkdir', '-p', '/sdcard/Android/data/dev.iconmanager/files')
    Invoke-Checked 'adb' @('push', $reference, '/sdcard/Android/data/dev.iconmanager/files/reference.hnt')
    $result = & adb shell am instrument -w dev.iconmanager.test/dev.iconmanager.DeviceTests 2>&1 | Out-String
    Write-Output $result
    if ($LASTEXITCODE -ne 0 -or $result -notmatch 'OK: \d+ checks passed') { throw 'Device checks failed' }
    New-Item -ItemType Directory -Force '.\app\build\outputs\checks' | Out-Null
    # Recent Android versions deny shell reads of files created by the app.
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new('adb', 'exec-out run-as dev.iconmanager cat files/device-check.hnt')
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $output = [System.IO.File]::Create((Join-Path (Get-Location) 'app\build\outputs\checks\device-check.hnt'))
    try {
        $process.StandardOutput.BaseStream.CopyTo($output)
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw $process.StandardError.ReadToEnd() }
    } finally {
        $output.Dispose()
        $process.Dispose()
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path (Get-Location) 'app\build\outputs\checks\device-check.hnt'))
    try {
        if ($archive.Entries.Count -ne 7 -or !$archive.GetEntry('preview/icon_small.jpg') -or !$archive.GetEntry('com.hihonor.android.launcher')) { throw 'Unexpected theme archive structure' }
    } finally { $archive.Dispose() }
} finally {
    if ($testsInstalled) {
        $cleanup = & adb shell am instrument -w -e cleanup true dev.iconmanager.test/dev.iconmanager.DeviceTests 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $cleanup -notmatch 'OK: \d+ checks passed') { Write-Warning "Device cleanup failed: $cleanup" }
    }
    if (Test-Path -LiteralPath $reference) { Remove-Item -LiteralPath $reference }
    Pop-Location
}
