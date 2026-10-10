param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$ErrorActionPreference = 'Stop'
$runtime = Join-Path (Resolve-Path -LiteralPath $InstallRoot).Path 'state'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
Set-Content -LiteralPath (Join-Path $runtime 'stop.request') -Value 'stop' -Encoding ASCII
Write-Host 'Stop requested; supervisor stops its own child process.'
