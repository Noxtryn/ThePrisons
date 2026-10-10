param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'), [switch]$Demo)
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
$taskState = Join-Path $InstallRoot 'state'
$taskPython = Join-Path $InstallRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) { throw 'Python runtime missing.' }
$env:DISCORD_OAUTH_CLIENT_SECRET = $null
if (-not $Demo) {
    $taskFile = Join-Path $taskState 'oauth-secret.dpapi'
    if (-not (Test-Path -LiteralPath $taskFile)) { throw 'Run Configure-NexoraDashboard.ps1 first, or use -Demo.' }
    $taskSecure = (Get-Content -LiteralPath $taskFile -Raw).Trim() | ConvertTo-SecureString
    $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecure)
    try { $env:DISCORD_OAUTH_CLIENT_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
    $taskSettings = Get-Content -LiteralPath (Join-Path $taskState 'settings.json') -Raw | ConvertFrom-Json
    foreach ($taskProperty in $taskSettings.PSObject.Properties) {
        if ($taskProperty.Name.StartsWith('DISCORD_') -and $taskProperty.Name -ne 'DISCORD_BOT_TOKEN') {
            [Environment]::SetEnvironmentVariable($taskProperty.Name, [string]$taskProperty.Value, 'Process')
        }
    }
    $taskBotFile = Join-Path $taskState 'token.dpapi'
    if (Test-Path -LiteralPath $taskBotFile) {
        $taskBotSecure = (Get-Content -LiteralPath $taskBotFile -Raw).Trim() | ConvertTo-SecureString
        $taskBotPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskBotSecure)
        try { $env:DISCORD_BOT_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskBotPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskBotPointer) }
    }
}
Set-Location -LiteralPath $InstallRoot
try {
    $taskArgs = @('-m', 'prisonsbot.dashboard', '--runtime', $taskState, '--health-file', (Join-Path $taskState 'health.json'))
    if ($Demo) { $taskArgs += '--demo' }
    & $taskPython @taskArgs
    exit $LASTEXITCODE
} finally {
    Remove-Item Env:DISCORD_OAUTH_CLIENT_SECRET -ErrorAction SilentlyContinue
    Remove-Item Env:DISCORD_BOT_TOKEN -ErrorAction SilentlyContinue
}
