param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'), [string]$GuildId, [switch]$StartAfterSave)
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
$runtime = Join-Path $InstallRoot 'state'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$guild = $GuildId
if (-not $guild) { $guild = Read-Host 'DISCORD_GUILD_ID (Server-ID)' }
if ($guild -notmatch '^\d{15,25}$') { throw 'Invalid server ID' }
$token = Read-Host 'DISCORD_BOT_TOKEN (hidden, never paste into chat)' -AsSecureString
if ($token.Length -lt 20) { throw 'Bot token is missing or too short' }
$token | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $runtime 'token.dpapi') -Encoding ASCII
$known = Get-Content -LiteralPath (Join-Path $InstallRoot 'server-config.example.json') -Raw | ConvertFrom-Json
$settings = [ordered]@{DISCORD_APPLICATION_ID='1557515544230371338'; DISCORD_GUILD_ID=$guild; NEXORA_COMMUNITY_ENABLED='false'; NEXORA_MIGRATION_APPROVED='false'; NEXORA_PUBLISH_ENABLED='false'}
foreach ($p in $known.channels.PSObject.Properties) {
    $settings['DISCORD_CHANNEL_' + $p.Name.ToUpper().Replace('-', '_')] = $p.Value
}
$settings | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'settings.json') -Encoding UTF8
Write-Host 'Encrypted token saved for this Windows user. All Discord write gates remain disabled.'
Write-Host 'Start with Start-Nexora.ps1. Obtain a read-only setup preview before enabling migration/community writes.'
if ($StartAfterSave) {
    $launcher = Join-Path $InstallRoot 'hosting\windows\Start-Nexora.ps1'
    Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $launcher + '"'),'-InstallRoot',('"' + $InstallRoot + '"'))
    Write-Host 'Supervisor launched hidden; check Status-Nexora.ps1 for actual gateway connection.'
}
