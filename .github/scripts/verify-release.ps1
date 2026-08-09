[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$gradleFile = Join-Path $repositoryRoot 'app/build.gradle.kts'
$versionMatches = [regex]::Matches(
    (Get-Content -Raw -LiteralPath $gradleFile),
    '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$'
)
if ($versionMatches.Count -ne 1) {
    throw "Expected exactly one Gradle versionName, found $($versionMatches.Count)"
}
$gradleVersion = $versionMatches[0].Groups[1].Value
if ($Version -cne $gradleVersion) {
    throw "Requested version '$Version' does not equal Gradle versionName '$gradleVersion'"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'app/build/outputs/apk/release'
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot $OutputDirectory
}

if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    throw "Release output directory does not exist: $OutputDirectory"
}

$fileName = "NSCheatManager-v$Version.apk"
$apkPath = Join-Path $OutputDirectory $fileName
$matchingApks = @(Get-ChildItem -LiteralPath $OutputDirectory -File -Filter 'NSCheatManager-v*.apk')
if ($matchingApks.Count -ne 1 -or $matchingApks[0].Name -cne $fileName) {
    throw "Expected exactly one versioned APK named '$fileName'; found: $($matchingApks.Name -join ', ')"
}
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    throw "Missing release APK: $apkPath"
}
if ((Get-Item -LiteralPath $apkPath).Length -le 0) {
    throw "Release APK is empty: $apkPath"
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash.ToLowerInvariant()
$checksumPath = "$apkPath.sha256"
$checksumLine = "$hash  $fileName"
[IO.File]::WriteAllText($checksumPath, "$checksumLine`n", [Text.Encoding]::ASCII)

$lines = @(Get-Content -LiteralPath $checksumPath)
if ($lines.Count -ne 1 -or $lines[0] -cne $checksumLine) {
    throw "Checksum file must contain exactly one canonical SHA-256 line"
}

Write-Output "Verified $fileName"
Write-Output $checksumLine
