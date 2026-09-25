<#
.SYNOPSIS
    Offline, Minecraft-free verification of the ChartaLandlords rule engine.

.DESCRIPTION
    Everything under `chartalandlords/doudizhu/game/engine/` is deliberately free of any
    Minecraft/Charta import: it only takes `int[]` rank values. This script compiles *only* that
    package plus the harness in `mods/chartalandlords/tools/logic-harness/` with a bare `javac`
    (empty classpath) and runs it with a bare `java`.

    The pure/adapter split is a directory-level contract, not a heuristic: if any file under
    `game/engine/` starts referencing `Card`, this script fails loudly. That keeps the rules,
    scoring and AI policy testable without booting the game.

    NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 decodes a BOM-less .ps1
    using the system ANSI codepage, so non-ASCII comments can be mangled - and a mangled byte
    sequence can even swallow the following newline, silently merging the next statement into the
    comment. Keep the tooling scripts ASCII.

.EXAMPLE
    & tools\logic-check.ps1
#>
[CmdletBinding()]
param(
    [string] $Project = ''
)

$ErrorActionPreference = 'Stop'

# Portable path join: the same script has to work from Windows PowerShell and from pwsh on Linux
# (GitHub Actions). Backslash-separated literals would break the latter.
if ([string]::IsNullOrWhiteSpace($Project)) {
    $Project = Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) 'mods') 'chartalandlords'
}

$engineDir = Join-Path $Project (Join-Path 'src' (Join-Path (Join-Path 'main' 'java') (Join-Path 'chartalandlords' (Join-Path 'doudizhu' (Join-Path 'game' 'engine')))))
$harnessDir = Join-Path $Project (Join-Path 'tools' 'logic-harness')
if (-not (Test-Path $engineDir)) { throw "engine sources not found: $engineDir" }
if (-not (Test-Path $harnessDir)) { throw "logic harness sources not found: $harnessDir" }

$pure = @(Get-ChildItem -Path $engineDir -Filter *.java | ForEach-Object { $_.FullName })
if ($pure.Count -eq 0) { throw "no engine sources found under $engineDir" }

$tainted = @()
foreach ($file in $pure) {
    $text = [System.IO.File]::ReadAllText($file)
    if ($text -match 'import\s+(net\.minecraft|dev\.lucaargolo|net\.neoforged)') {
        $tainted += (Split-Path $file -Leaf)
    }
}
if ($tainted.Count -gt 0) {
    $joined = $tainted -join ', '
    throw "logic-check: game/engine must stay Minecraft-free, but these import game types: $joined"
}

$harness = @(Get-ChildItem -Path $harnessDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($harness.Count -eq 0) { throw "no harness sources found under $harnessDir" }

$outDir = Join-Path $Project (Join-Path 'build' 'logic-check')
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host ("engine (pure): " + (($pure | ForEach-Object { Split-Path $_ -Leaf }) -join ', '))
Write-Host ("harness      : " + (($harness | ForEach-Object { Split-Path $_ -Leaf }) -join ', '))

$argFile = Join-Path $env:TEMP ("chartalandlords-logic-{0}.args" -f ([guid]::NewGuid().ToString('N')))
$lines = @('-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', $outDir)
$lines += $pure
$lines += $harness
# javac treats a leading BOM as an illegal option, so write UTF-8 without one.
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

try {
    & javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "logic-check: javac failed ($LASTEXITCODE)" }
} finally {
    Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue
}

foreach ($main in 'HarnessMain', 'AiHarnessMain', 'PlayHarnessMain') {
    & java -cp $outDir "chartalandlords.doudizhu.game.engine.$main"
    if ($LASTEXITCODE -ne 0) { throw "logic-check: $main failed ($LASTEXITCODE)" }
}

Write-Host 'logic-check OK' -ForegroundColor Green
