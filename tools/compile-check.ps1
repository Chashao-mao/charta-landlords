<#
.SYNOPSIS
    Offline javac compile check for the doudizhu mod sources.

.DESCRIPTION
    The Gradle wrapper in this workspace points at Gradle 9.2.1, which is not present in the
    local wrapper cache, and the original build machine paths (E:\codex.gongzuo\...) do not exist
    here. To keep a fast, deterministic verification loop we compile the mod sources directly with
    `javac` against:
      * the ModDevGradle merged NeoForge + Minecraft artifact already produced in build/moddev,
      * the Charta jar shipped in libs/,
      * every non-source jar found in the local Gradle module cache (jetbrains annotations,
        authlib, lwjgl, guava, ...).

    This is a *compile* check only: it catches type errors, missing imports and signature drift.
    Game logic correctness is covered by DoudizhuGameTests (run through Gradle).

.PARAMETER Project
    Path to the mod project (default: the doudizhu module next to this script).

.PARAMETER IncludeTests
    Also compile src/main/java/**/test/** (GameTest sources).

.PARAMETER OutDir
    Output directory for the compiled classes. Defaults to build/compile-check. Pass a unique value
    when several agents run this script against the same project at the same time.

.EXAMPLE
    pwsh -File tools/compile-check.ps1
#>
[CmdletBinding()]
param(
    [string] $Project = (Join-Path (Split-Path $PSScriptRoot -Parent) 'mods\chartalandlords'),
    [switch] $IncludeTests,
    [string] $OutDir = ''
)

$ErrorActionPreference = 'Stop'

function Get-ModdevJar {
    param([string] $Project)
    $dir = Join-Path $Project 'build\moddev\artifacts'
    if (-not (Test-Path $dir)) {
        throw "No ModDevGradle artifacts under $dir. Run a Gradle build once to populate them."
    }
    # Prefer a merged artifact: it carries both Minecraft and NeoForge classes.
    $merged = Get-ChildItem $dir -Filter 'neoforge-*-merged.jar' | Sort-Object Name -Descending
    if ($merged) { return $merged[0].FullName }
    $any = Get-ChildItem $dir -Filter 'neoforge-*.jar' | Sort-Object Name -Descending
    if ($any) { return $any[0].FullName }
    throw "No NeoForge artifact found in $dir"
}

$jarList = [System.Collections.Generic.List[string]]::new()
$jarList.Add((Get-ModdevJar -Project $Project))

$charta = Get-ChildItem (Join-Path $Project 'libs') -Filter 'charta-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $charta) { throw "Charta jar not found under $Project\libs" }
$jarList.Add($charta.FullName)

$gradleCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
if (Test-Path $gradleCache) {
    Get-ChildItem $gradleCache -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(-sources|-javadoc)\.jar$' } |
        ForEach-Object { $jarList.Add($_.FullName) }
}

$classpath = ($jarList | Select-Object -Unique) -join ';'

$srcRoot = Join-Path $Project 'src\main\java'
$sources = Get-ChildItem $srcRoot -Recurse -Filter *.java
if (-not $IncludeTests) {
    $sources = $sources | Where-Object { $_.FullName -notmatch '\\test\\' }
}

$outDir = if ($OutDir) { $OutDir } else { Join-Path $Project 'build\compile-check' }
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$argFile = Join-Path $env:TEMP ("doudizhu-javac-{0}.args" -f ([guid]::NewGuid().ToString('N')))
$lines = @('-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', $outDir, '-classpath', $classpath)
$lines += $sources | ForEach-Object { $_.FullName }
# javac treats a leading BOM as an illegal option, so write UTF-8 without one.
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

try {
    & javac "@$argFile"
    $code = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue
}

if ($code -eq 0) {
    Write-Host "compile-check OK ($($sources.Count) files)" -ForegroundColor Green
} else {
    throw "compile-check FAILED (javac exit $code)"
}
