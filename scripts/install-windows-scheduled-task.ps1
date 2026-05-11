#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$TaskName = "TradingBotBackend"
$AppDir = "C:\TradingBot\backend"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Assert-Admin {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run PowerShell as Administrator, then run this installer again."
  }
}

Assert-Admin

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
  Write-Host "Java is missing. Install dependencies first:"
  Write-Host "  .\bootstrap-windows-pc.ps1"
  Write-Host "Or manually install Microsoft OpenJDK 17:"
  Write-Host "  https://learn.microsoft.com/java/openjdk/download"
  throw "Java 17+ is required."
}

if (-not (Test-Path (Join-Path $ScriptDir "tradingbot-backend.jar"))) {
  throw "Missing tradingbot-backend.jar. Build with .\scripts\build-backend-release.ps1, then install from release\backend-windows."
}

New-Item -ItemType Directory -Force -Path $AppDir, (Join-Path $AppDir "data"), (Join-Path $AppDir "logs") | Out-Null

Copy-Item (Join-Path $ScriptDir "tradingbot-backend.jar") (Join-Path $AppDir "tradingbot-backend.jar") -Force
Copy-Item (Join-Path $ScriptDir "run-backend-release.ps1") (Join-Path $AppDir "run-backend-release.ps1") -Force

$PackagedDb = Join-Path $ScriptDir "data\tradingbot.db"
$InstalledDb = Join-Path $AppDir "data\tradingbot.db"
if (Test-Path $PackagedDb) {
  if (-not (Test-Path $InstalledDb)) {
    Copy-Item $PackagedDb $InstalledDb -Force
    Write-Host "Installed packaged DB: $InstalledDb"
  } else {
    Write-Host "Existing DB kept: $InstalledDb"
    Write-Host "Packaged DB was not copied because overwriting live trading state is unsafe."
  }
}

$EnvPath = Join-Path $AppDir ".env"
if (-not (Test-Path $EnvPath)) {
  Copy-Item (Join-Path $ScriptDir ".env.example") $EnvPath -Force
}

$Action = New-ScheduledTaskAction `
  -Execute "powershell.exe" `
  -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$AppDir\run-backend-release.ps1`""
$Trigger = New-ScheduledTaskTrigger -AtStartup
$Principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -RunLevel Highest
$Settings = New-ScheduledTaskSettingsSet `
  -StartWhenAvailable `
  -RestartCount 999 `
  -RestartInterval (New-TimeSpan -Minutes 1) `
  -ExecutionTimeLimit (New-TimeSpan -Days 3650) `
  -MultipleInstances IgnoreNew

Register-ScheduledTask `
  -TaskName $TaskName `
  -Action $Action `
  -Trigger $Trigger `
  -Principal $Principal `
  -Settings $Settings `
  -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName

Write-Host "Installed and started scheduled task: $TaskName"
Write-Host "App dir: $AppDir"
Write-Host "Health check:"
Write-Host "  Invoke-RestMethod http://localhost:7070/api/system/health"
Write-Host "Logs:"
Write-Host "  $AppDir\logs\backend.out.log"
Write-Host "  $AppDir\logs\backend.err.log"
