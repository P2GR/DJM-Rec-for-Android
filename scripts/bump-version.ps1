param(
    [ValidateSet("major", "minor", "patch")]
    [string]$Part = "patch",
    [string]$Version
)

$ErrorActionPreference = "Stop"
$versionFile = Join-Path $PSScriptRoot "..\version.properties"
$lines = Get-Content -LiteralPath $versionFile
$currentName = ($lines | Where-Object { $_ -match '^VERSION_NAME=' }) -replace '^VERSION_NAME=', ''
$currentCode = [int](($lines | Where-Object { $_ -match '^VERSION_CODE=' }) -replace '^VERSION_CODE=', '')

if ($Version) {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        throw "Version must use major.minor.patch, for example 0.35.0"
    }
    $nextName = $Version
} else {
    $parts = @($currentName.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    switch ($Part) {
        "major" { $parts[0]++; $parts[1] = 0; $parts[2] = 0 }
        "minor" { $parts[1]++; $parts[2] = 0 }
        "patch" { $parts[2]++ }
    }
    $nextName = $parts -join '.'
}

$nextCode = $currentCode + 1
[System.IO.File]::WriteAllLines($versionFile, @(
    "VERSION_NAME=$nextName"
    "VERSION_CODE=$nextCode"
))

Write-Host "Version bumped: $currentName ($currentCode) -> $nextName ($nextCode)"
Write-Host "Release tag: v$nextName"
