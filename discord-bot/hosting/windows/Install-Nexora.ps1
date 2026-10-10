param([Parameter(Mandatory=$true)][string]$Python, [string]$InstallRoot = (Join-Path $PSScriptRoot '..\..'))
$ErrorActionPreference = 'Stop'
$InstallRoot = (Resolve-Path -LiteralPath $InstallRoot).Path
& $Python -m venv (Join-Path $InstallRoot '.venv')
if ($LASTEXITCODE -ne 0) { throw 'Could not create Python environment' }
$runtimePython = Join-Path $InstallRoot '.venv\Scripts\python.exe'
& $runtimePython -m pip install -r (Join-Path $InstallRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
Set-Location -LiteralPath $InstallRoot
& $runtimePython -m prisonsbot check
if ($LASTEXITCODE -ne 0) { throw 'Content check failed' }
Write-Host 'Runtime installed. Configure-Nexora.ps1 stores credentials securely.'
