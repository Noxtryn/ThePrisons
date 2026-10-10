param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
$taskState = Join-Path $InstallRoot 'state'
New-Item -ItemType Directory -Path $taskState -Force | Out-Null
Write-Host 'Discord Developer Portal: OAuth2 > Redirects'
Write-Host 'Add http://127.0.0.1:8765/auth/callback'
Write-Host 'Enter the OAuth Client Secret below, NOT the bot token.'
$taskSecret = Read-Host 'OAuth Client Secret (hidden)' -AsSecureString
if ($taskSecret.Length -lt 16) { throw 'OAuth Client Secret is missing or too short.' }
$taskSecret | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $taskState 'oauth-secret.dpapi')
Write-Host 'OAuth Client Secret encrypted for this Windows user.'
