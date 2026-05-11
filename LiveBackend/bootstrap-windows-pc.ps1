#Requires -Version 5.1
param(
  [switch]$WithFrontend
)

$ErrorActionPreference = "Stop"

function Assert-Admin {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run PowerShell as Administrator, then run this script again."
  }
}

function Install-WingetPackage {
  param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$Name
  )

  Write-Host "Installing $Name ($Id)..."
  winget install --id $Id --exact --accept-source-agreements --accept-package-agreements
}

Assert-Admin

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
  Write-Host "winget is not available on this Windows install."
  Write-Host "Manual dependency downloads:"
  Write-Host "- Git for Windows: https://git-scm.com/download/win"
  Write-Host "- Microsoft OpenJDK 17: https://learn.microsoft.com/java/openjdk/download"
  Write-Host "- SQLite tools (optional): https://www.sqlite.org/download.html"
  Write-Host "- Node.js LTS (optional frontend only): https://nodejs.org/"
  throw "Install winget/App Installer or install the dependencies manually, then rerun setup."
}

Install-WingetPackage -Id "Git.Git" -Name "Git for Windows"
Install-WingetPackage -Id "Microsoft.OpenJDK.17" -Name "Microsoft OpenJDK 17"

try {
  Install-WingetPackage -Id "SQLite.SQLite" -Name "SQLite command-line tools"
} catch {
  Write-Warning "SQLite winget install failed. It is optional for runtime, useful for DB inspection/backups."
}

if ($WithFrontend) {
  Install-WingetPackage -Id "OpenJS.NodeJS.LTS" -Name "Node.js LTS"
}

Write-Host ""
Write-Host "Dependency install complete. Open a new PowerShell window so PATH changes apply."
Write-Host ""
Write-Host "Next steps after git clone/git pull:"
Write-Host "  .\scripts\build-backend-release.ps1"
Write-Host "  cd .\release\backend-windows"
Write-Host "  .\install-windows-scheduled-task.ps1"
