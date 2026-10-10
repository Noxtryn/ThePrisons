param([string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$runtime = Join-Path (Resolve-Path -LiteralPath $InstallRoot).Path 'state'
foreach ($name in @('supervisor.json', 'health.json')) {
    $file = Join-Path $runtime $name
    if (Test-Path -LiteralPath $file) {
        Write-Host $name
        Get-Content -LiteralPath $file
    } else { Write-Host "$name : not started" }
}
