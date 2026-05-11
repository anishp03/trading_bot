#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$TaskName = "TradingBotBackend"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw "Run PowerShell as Administrator, then run this uninstaller again."
}

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Write-Host "Removed scheduled task: $TaskName"
Write-Host "Files under C:\TradingBot\backend were left in place."
