param([switch]$SkipLint)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $tasks = @('testExperimentalUnitTest', 'assembleExperimental')
    if (-not $SkipLint) { $tasks = @('lintExperimental') + $tasks }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Experimental build failed ($LASTEXITCODE)." }
    Get-ChildItem -LiteralPath 'app/build/outputs/apk/experimental' -Filter '*.apk' | ForEach-Object {
        $digest = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$digest  $($_.Name)" | Set-Content -LiteralPath ($_.FullName + '.sha256') -Encoding ascii
        Write-Output $_.FullName
    }
} finally { Pop-Location }
