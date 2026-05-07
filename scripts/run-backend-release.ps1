#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$AppDir = if ($env:TRADINGBOT_APP_DIR) { $env:TRADINGBOT_APP_DIR } else { "C:\TradingBot\backend" }
$JarPath = if ($env:TRADINGBOT_JAR_PATH) { $env:TRADINGBOT_JAR_PATH } else { Join-Path $AppDir "tradingbot-backend.jar" }
$DataDir = Join-Path $AppDir "data"
$LogDir = Join-Path $AppDir "logs"
$DbPath = if ($env:TRADINGBOT_DB_PATH) { $env:TRADINGBOT_DB_PATH } else { Join-Path $DataDir "tradingbot.db" }
$EnvFile = Join-Path $AppDir ".env"

New-Item -ItemType Directory -Force -Path $DataDir, $LogDir | Out-Null

if (Test-Path $EnvFile) {
  Get-Content $EnvFile | ForEach-Object {
    $Line = $_.Trim()
    if (-not $Line -or $Line.StartsWith("#") -or $Line -notmatch "=") { return }
    $Name, $Value = $Line.Split("=", 2)
    [Environment]::SetEnvironmentVariable($Name.Trim(), $Value.Trim(), "Process")
  }
}

$Version = if ($env:TRADINGBOT_VERSION) { $env:TRADINGBOT_VERSION } else { "windows-release" }
$Build = if ($env:TRADINGBOT_BUILD) { $env:TRADINGBOT_BUILD } else { "manual" }
$BindHost = if ($env:TRADINGBOT_BIND_HOST) { $env:TRADINGBOT_BIND_HOST } else { "127.0.0.1" }
$Port = if ($env:TRADINGBOT_PORT) { $env:TRADINGBOT_PORT } else { "7070" }
$OutLog = Join-Path $LogDir "backend.out.log"
$ErrLog = Join-Path $LogDir "backend.err.log"

Set-Location $AppDir

& java `
  "-Dtradingbot.db.path=$DbPath" `
  "-Dtradingbot.version=$Version" `
  "-Dtradingbot.build=$Build" `
  "-Dtradingbot.bindHost=$BindHost" `
  "-Dtradingbot.port=$Port" `
  -jar "$JarPath" `
  1>> "$OutLog" `
  2>> "$ErrLog"

exit $LASTEXITCODE
