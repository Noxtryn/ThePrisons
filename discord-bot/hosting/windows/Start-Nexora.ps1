param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
$runtime = Join-Path $InstallRoot 'state'
$credentialFile = Join-Path $runtime 'token.dpapi'
$configFile = Join-Path $runtime 'settings.json'
if (-not (Test-Path -LiteralPath $credentialFile) -or -not (Test-Path -LiteralPath $configFile)) {
    throw 'Run Configure-Nexora.ps1 first; credentials/configuration are missing.'
}
$settings = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
foreach ($property in $settings.PSObject.Properties) {
    if ($property.Name -ne 'DISCORD_BOT_TOKEN') {
        [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
    }
}
$secureToken = Get-Content -LiteralPath $credentialFile -Raw | ConvertTo-SecureString
$tokenPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try {
    $env:DISCORD_BOT_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPtr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPtr)
}
$python = Join-Path $InstallRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Python runtime missing; run Install-Nexora.ps1.' }
$stop = Join-Path $runtime 'stop.request'
if (Test-Path -LiteralPath $stop) { Remove-Item -LiteralPath $stop }
try {
    Set-Location -LiteralPath $InstallRoot
    & $python -m prisonsbot.hosting --runtime $runtime
    exit $LASTEXITCODE
} finally {
    Remove-Item Env:DISCORD_BOT_TOKEN -ErrorAction SilentlyContinue
}
