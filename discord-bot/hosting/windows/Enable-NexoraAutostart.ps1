param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
$script = Join-Path $InstallRoot 'hosting\windows\Start-Nexora.ps1'
$startup = [Environment]::GetFolderPath('Startup')
$target = Join-Path $startup 'Nexora Core.vbs'
$command = 'powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $script + '" -InstallRoot "' + $InstallRoot + '"'
$quoted = $command.Replace('"', '""')
('CreateObject("WScript.Shell").Run "' + $quoted + '", 0, False') | Set-Content -LiteralPath $target -Encoding ASCII
Write-Host 'Nexora starts hidden when this Windows user logs in. No admin task/service is required.'
