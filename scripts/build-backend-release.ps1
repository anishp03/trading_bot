#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$BackendDir = Join-Path $ProjectRoot "backend"
$ReleaseDir = Join-Path $ProjectRoot "release\backend-windows"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
  Write-Host "Java is missing. Install dependencies first:"
  Write-Host "  Open PowerShell as Administrator"
  Write-Host "  .\scripts\bootstrap-windows-pc.ps1"
  throw "Java 17+ is required."
}

$JavaVersionOutput = (& java -version 2>&1) -join "`n"
if ($JavaVersionOutput -notmatch 'version "((1[7-9])|([2-9][0-9]))\.') {
  Write-Host $JavaVersionOutput
  Write-Host "Java 17+ is required. Install it with:"
  Write-Host "  Open PowerShell as Administrator"
  Write-Host "  .\scripts\bootstrap-windows-pc.ps1"
  throw "Unsupported Java version."
}

Push-Location $BackendDir
try {
  .\mvnw.cmd clean package
} finally {
  Pop-Location
}

New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
Copy-Item (Join-Path $BackendDir "target\backend-0.0.1-SNAPSHOT-all.jar") (Join-Path $ReleaseDir "tradingbot-backend.jar") -Force
Copy-Item (Join-Path $ProjectRoot "scripts\run-backend-release.ps1") $ReleaseDir -Force
Copy-Item (Join-Path $ProjectRoot "scripts\install-windows-scheduled-task.ps1") $ReleaseDir -Force
Copy-Item (Join-Path $ProjectRoot "scripts\uninstall-windows-scheduled-task.ps1") $ReleaseDir -Force
Copy-Item (Join-Path $ProjectRoot "scripts\bootstrap-windows-pc.ps1") $ReleaseDir -Force
Copy-Item (Join-Path $ProjectRoot ".env.example") $ReleaseDir -Force

@"
Trading Bot Backend Release for Windows

Files:
- tradingbot-backend.jar: standalone Java backend
- run-backend-release.ps1: local runner
- install-windows-scheduled-task.ps1: installs auto-start scheduled task
- uninstall-windows-scheduled-task.ps1: removes scheduled task
- bootstrap-windows-pc.ps1: installs Windows dependencies
- .env.example: example env config

Recommended install path:
C:\TradingBot\backend

Install:
Open PowerShell as Administrator:
  cd path\to\release\backend-windows
  .\install-windows-scheduled-task.ps1

Check:
  Invoke-RestMethod http://localhost:7070/api/system/health
"@ | Set-Content -Encoding UTF8 (Join-Path $ReleaseDir "README.txt")

Write-Host "Windows backend release written to $ReleaseDir"
