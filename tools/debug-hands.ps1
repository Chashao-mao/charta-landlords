<#
.SYNOPSIS
    Run the custom-hand debugger of the pure rule engine (no Minecraft needed).

.DESCRIPTION
    Compiles ONLY `chartalandlords/doudizhu/game/engine/` plus the harness in
    `mods/chartalandlords/tools/logic-harness/` with a bare javac (empty classpath) and then runs
    DebugHandsMain with the arguments you pass through. Everything you give after the script name is
    forwarded verbatim to the Java program, so the Java usage text is the reference:

        & tools\debug-hands.ps1 --help

    With no arguments this runs the debugger in self-test mode (assertions + a sample report), which
    is exactly what tools\logic-check.ps1 does as part of the verification chain.

.EXAMPLE
    & tools\debug-hands.ps1 --players 4 --hand 0 "3 3 3 4 5" --hand 1 "S B 7 8 9" --last "0:5 5" --turn 1

.EXAMPLE
    & tools\debug-hands.ps1 --players 4 --hand 0 "S B 3 4 5" --rule mixed-joker-rocket --probe "0:S B"

.NOTES
    ASCII-only on purpose: Windows PowerShell 5.1 decodes a BOM-less .ps1 using the system ANSI
    codepage, so non-ASCII comments can be mangled - and a mangled byte sequence can even swallow
    the following newline. Keep the tooling scripts ASCII. (The Java sources are UTF-8 and javac is
    told so.)
#>

$ErrorActionPreference = 'Stop'

# No param block: every argument is forwarded to Java unchanged (a param block would make PowerShell
# try to bind --players / --hand as its own parameter names).
$passthrough = @($args)

$project = Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) 'mods') 'chartalandlords'
$engineDir = Join-Path $project (Join-Path 'src' (Join-Path 'main' (Join-Path 'java' (Join-Path 'chartalandlords' (Join-Path 'doudizhu' (Join-Path 'game' 'engine'))))))
$harnessDir = Join-Path $project (Join-Path 'tools' 'logic-harness')
if (-not (Test-Path $engineDir)) { throw "engine sources not found: $engineDir" }
if (-not (Test-Path $harnessDir)) { throw "harness sources not found: $harnessDir" }

$sources = @(Get-ChildItem -Path $engineDir -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Path $harnessDir -Filter *.java | ForEach-Object { $_.FullName })

$outDir = Join-Path $project (Join-Path 'build' 'debug-hands')
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# GetTempPath(), not $env:TEMP: Linux has no TEMP and Join-Path rejects an empty Path.
$argFile = Join-Path ([System.IO.Path]::GetTempPath()) ("chartalandlords-debug-{0}.args" -f ([guid]::NewGuid().ToString('N')))
$lines = @('-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', $outDir)
$lines += $sources
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
try {
    & javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "debug-hands: javac failed ($LASTEXITCODE)" }
} finally {
    Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue
}

# Chinese report text has to survive the console: PowerShell 5.1 defaults to the ANSI codepage.
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    Write-Verbose "could not switch the console to UTF-8: $($_.Exception.Message)"
}

& java '-Dfile.encoding=UTF-8' -cp $outDir 'chartalandlords.doudizhu.game.engine.DebugHandsMain' @passthrough
exit $LASTEXITCODE
