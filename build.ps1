param([switch]$Test, [switch]$Lint, [switch]$Instrument, [switch]$Release)
$ErrorActionPreference = 'Stop'
$tasks = @('assembleDebug')
if ($Test) { $tasks += 'testDebugUnitTest' }
if ($Lint) { $tasks += 'lintDebug' }
if ($Instrument) { $tasks += 'assembleDebugAndroidTest' }
if ($Release) { $tasks += 'assembleRelease' }
& (Join-Path $PSScriptRoot 'gradlew.bat') @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
