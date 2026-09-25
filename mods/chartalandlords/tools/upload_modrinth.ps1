# upload_modrinth.ps1 - publish this mod to Modrinth in one command.
#
#   powershell -ExecutionPolicy Bypass -File tools\upload_modrinth.ps1 -Token <token>
#   powershell -ExecutionPolicy Bypass -File tools\upload_modrinth.ps1 -DryRun      # no network, no token
#
# What it does:
#   1. reads modrinth\upload.json + summary.txt + description.md + changelog-*.md (all UTF-8),
#   2. resolves the project by slug (creates it if it does not exist yet, icon included),
#   3. updates the project icon when the project already exists,
#   4. uploads the jar in dist\ as a new version with the changelog and a required dependency on Charta.
#
# Token scopes needed (modrinth.com -> Settings -> PATs): PROJECT_CREATE, PROJECT_WRITE, VERSION_CREATE.
# The token is only ever sent to api.modrinth.com. Nothing is written to disk except an optional log.
#
# ASCII-only on purpose: Windows PowerShell 5.1 decodes BOM-less .ps1 files with the system ANSI code page,
# so non-ASCII comments would be mis-decoded (and can even swallow the following newline). All non-ASCII
# text lives in the UTF-8 data files under modrinth\.

[CmdletBinding()]
param(
    [string]$Token = $env:MODRINTH_TOKEN,
    [string]$ConfigPath = '',
    [switch]$DryRun,
    [switch]$SkipIcon,
    [switch]$PublishProject
)

$ErrorActionPreference = 'Stop'
# Invoke-RestMethod on 5.1 defaults to old TLS versions; Modrinth requires 1.2+.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$modDir = Split-Path -Parent $scriptDir
$workspace = Split-Path -Parent (Split-Path -Parent $modDir)
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path $modDir 'modrinth\upload.json'
}

$api = 'https://api.modrinth.com/v2'
$userAgent = 'ChartaLandlords-release-script/1.0'

function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function New-MultipartBody {
    # PowerShell 5.1 has no -Form parameter, so the multipart body is assembled by hand.
    param(
        [hashtable]$TextFields,
        [hashtable]$FileFields
    )
    $boundary = [Guid]::NewGuid().ToString('N')
    $stream = New-Object System.IO.MemoryStream
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    foreach ($name in $TextFields.Keys) {
        $header = "--$boundary`r`nContent-Disposition: form-data; name=`"$name`"`r`n`r`n"
        $bytes = $utf8.GetBytes($header + [string]$TextFields[$name] + "`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
    }
    foreach ($name in $FileFields.Keys) {
        $file = $FileFields[$name]
        $header = "--$boundary`r`nContent-Disposition: form-data; name=`"$name`"; filename=`"$($file.Name)`"`r`n" +
                  "Content-Type: application/octet-stream`r`n`r`n"
        $bytes = $utf8.GetBytes($header)
        $stream.Write($bytes, 0, $bytes.Length)
        $content = [System.IO.File]::ReadAllBytes($file.FullName)
        $stream.Write($content, 0, $content.Length)
        $bytes = $utf8.GetBytes("`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
    }
    $tail = $utf8.GetBytes("--$boundary--`r`n")
    $stream.Write($tail, 0, $tail.Length)
    return @{ Body = $stream.ToArray(); ContentType = "multipart/form-data; boundary=$boundary" }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [byte[]]$Body = $null,
        [string]$ContentType = 'application/json'
    )
    $headers = @{ 'User-Agent' = $userAgent; 'Authorization' = $Token }
    $params = @{ Method = $Method; Uri = $Uri; Headers = $headers; TimeoutSec = 120 }
    if ($null -ne $Body) {
        $params['Body'] = $Body
        $params['ContentType'] = $ContentType
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $response = $_.Exception.Response
        $detail = $_.Exception.Message
        if ($null -ne $response) {
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $detail = $reader.ReadToEnd()
            } catch { }
            throw ("{0} {1} failed: HTTP {2} {3}" -f $Method, $Uri, [int]$response.StatusCode, $detail)
        }
        throw ("{0} {1} failed: {2}" -f $Method, $Uri, $detail)
    }
}

function Resolve-ProjectId([string]$Slug) {
    # Slugs work in most endpoints, but version dependencies want a real project id, so always resolve it.
    try {
        $project = Invoke-Api -Method Get -Uri "$api/project/$Slug"
        return $project.id
    } catch {
        return $null
    }
}

# ----------------------------------------------------------------------------- read the package

if (-not (Test-Path $ConfigPath)) { throw "config not found: $ConfigPath" }
$config = (Read-Utf8 $ConfigPath) | ConvertFrom-Json
$modrinthDir = Split-Path -Parent (Resolve-Path $ConfigPath).Path

$summaryPath = Join-Path $modrinthDir $config.project.summary_file
$bodyPath = Join-Path $modrinthDir $config.project.body_file
$changelogPath = Join-Path $modrinthDir $config.version.changelog_file
$jarPath = Join-Path $workspace $config.files.jar
$iconPath = Join-Path $workspace $config.files.icon

foreach ($path in @($summaryPath, $bodyPath, $changelogPath, $jarPath)) {
    if (-not (Test-Path $path)) { throw "missing file: $path" }
}
if (-not $SkipIcon -and -not (Test-Path $iconPath)) { throw "missing icon: $iconPath (run: gradlew dist)" }

$summary = (Read-Utf8 $summaryPath).Trim()
$body = Read-Utf8 $bodyPath
$changelog = Read-Utf8 $changelogPath
$projectData = [ordered]@{
    slug                   = $config.project.slug
    title                  = $config.project.title
    description            = $summary
    body                   = $body
    categories             = @($config.project.categories)
    additional_categories  = @($config.project.additional_categories)
    license_id             = $config.project.license_id
    project_type           = $config.project.project_type
    environment            = $config.project.environment
    is_draft               = [bool]$config.project.is_draft
}
if ($PublishProject) {
    # "approved" = public visibility; Modrinth still runs its review. Draft is the safe default.
    $projectData['status'] = 'approved'
    $projectData['is_draft'] = $false
}
if ($config.project.issues_url) { $projectData['issues_url'] = $config.project.issues_url }
if ($config.project.source_url) { $projectData['source_url'] = $config.project.source_url }

$dependencies = @()
if ($config.version.dependencies) {
    $dependencies = @($config.version.dependencies)
}

$versionData = [ordered]@{
    name           = $config.version.name
    version_number = $config.version.version_number
    changelog      = $changelog
    version_type   = $config.version.version_type
    game_versions  = @($config.version.game_versions)
    loaders        = @($config.version.loaders)
    environment    = $config.version.environment
    project_id     = '<resolved at runtime>'
    file_parts     = @('file')
    primary_file   = 'file'
}

$jar = Get-Item $jarPath
$icon = if (Test-Path $iconPath) { Get-Item $iconPath } else { $null }
$summaryLength = $summary.Length

Write-Host "ChartaLandlords -> Modrinth"
Write-Host ("  config      : {0}" -f $ConfigPath)
Write-Host ("  slug        : {0}" -f $config.project.slug)
Write-Host ("  version     : {0} ({1})" -f $config.version.version_number, $config.version.version_type)
Write-Host ("  summary     : {0} chars (limit 256)" -f $summaryLength)
Write-Host ("  body        : {0} chars" -f $body.Length)
Write-Host ("  changelog   : {0} chars" -f $changelog.Length)
Write-Host ("  jar         : {0} ({1} bytes)" -f $jar.Name, $jar.Length)
if ($icon) { Write-Host ("  icon        : {0} ({1} bytes, limit 256 KiB)" -f $icon.Name, $icon.Length) }
Write-Host ("  game/loader : {0} / {1}" -f ($config.version.game_versions -join ','), ($config.version.loaders -join ','))
Write-Host ("  dependency  : {0}" -f (($dependencies | ForEach-Object { "$($_.project_slug) [$($_.dependency_type)]" }) -join ', '))

if ($summaryLength -gt 256) { throw "summary is longer than Modrinth's 256 character limit" }
if ($icon -and $icon.Length -gt 262144) { throw "icon is larger than Modrinth's 256 KiB limit" }

if ($DryRun) {
    Write-Host ''
    Write-Host 'DRY RUN - nothing was sent. Payloads that would be uploaded:'
    Write-Host ''
    Write-Host ("POST {0}/project            (multipart: data + icon)" -f $api)
    Write-Host ($projectData | ConvertTo-Json -Depth 6)
    Write-Host ''
    Write-Host ("POST {0}/version            (multipart: data + file)" -f $api)
    Write-Host ($versionData | ConvertTo-Json -Depth 6)
    Write-Host ''
    Write-Host ("PATCH {0}/project/<slug>/icon?ext=png   (raw image body)" -f $api)
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw 'no token: pass -Token or set $env:MODRINTH_TOKEN (scopes: PROJECT_CREATE, PROJECT_WRITE, VERSION_CREATE)'
}

# Preflight: on Windows a shell without TLS credentials fails every HTTPS request with
# schannel SEC_E_NO_CREDENTIALS (0x8009030E). That is an environment problem, not a Modrinth one,
# so say so instead of failing later with a confusing message.
try {
    Invoke-Api -Method Get -Uri "$api/tag/loader" | Out-Null
} catch {
    Write-Warning ("preflight (GET /tag/loader) failed: {0}" -f $_.Exception.Message)
    Write-Warning 'If that mentions SEC_E_NO_CREDENTIALS / schannel / "credentials", this shell cannot do TLS.'
    Write-Warning 'Run the script from a normal PowerShell window (not a restricted sandbox) and retry.'
    throw 'network preflight failed'
}

# ----------------------------------------------------------------------------- 1) project

$projectId = Resolve-ProjectId -Slug $config.project.slug
if ($null -eq $projectId) {
    Write-Host ''
    Write-Host ("creating project '{0}' ..." -f $config.project.slug)
    $fields = @{ data = ($projectData | ConvertTo-Json -Depth 6 -Compress) }
    $files = @{}
    if ($icon) { $files['icon'] = $icon }
    $payload = New-MultipartBody -TextFields $fields -FileFields $files
    $created = Invoke-Api -Method Post -Uri "$api/project" -Body $payload.Body -ContentType $payload.ContentType
    $projectId = $created.id
    Write-Host ("  created: id={0}  https://modrinth.com/mod/{1}" -f $projectId, $created.slug)
} else {
    Write-Host ''
    Write-Host ("project '{0}' already exists (id={1}); leaving its fields alone" -f $config.project.slug, $projectId)
    if ($icon -and -not $SkipIcon) {
        Write-Host '  updating the project icon ...'
        $bytes = [System.IO.File]::ReadAllBytes($icon.FullName)
        $headers = @{ 'User-Agent' = $userAgent; 'Authorization' = $Token }
        Invoke-RestMethod -Method Patch -Uri "$api/project/$projectId/icon?ext=png" -Headers $headers `
            -ContentType 'image/png' -Body $bytes -TimeoutSec 120 | Out-Null
        Write-Host '  icon updated'
    } else {
        Write-Host '  icon left alone (-SkipIcon)'
    }
}

# ----------------------------------------------------------------------------- 2) version

$resolvedDependencies = @()
foreach ($dependency in $dependencies) {
    $depId = $null
    if ($dependency.project_id) { $depId = $dependency.project_id }
    elseif ($dependency.project_slug) { $depId = Resolve-ProjectId -Slug $dependency.project_slug }
    if ($null -eq $depId) {
        Write-Warning ("could not resolve dependency '{0}'; uploading without it" -f $dependency.project_slug)
        continue
    }
    $resolvedDependencies += @{ project_id = $depId; dependency_type = $dependency.dependency_type }
}
$versionData['project_id'] = $projectId
if ($resolvedDependencies.Count -gt 0) { $versionData['dependencies'] = $resolvedDependencies }

Write-Host ''
Write-Host ("uploading version {0} ..." -f $config.version.version_number)
$payload = New-MultipartBody -TextFields @{ data = ($versionData | ConvertTo-Json -Depth 6 -Compress) } `
    -FileFields @{ file = $jar }
$version = Invoke-Api -Method Post -Uri "$api/version" -Body $payload.Body -ContentType $payload.ContentType

Write-Host ''
Write-Host 'done.'
Write-Host ("  version id : {0}" -f $version.id)
Write-Host ("  page       : https://modrinth.com/mod/{0}/version/{1}" -f $config.project.slug, $version.version_number)
Write-Host ("  file       : {0} ({1} bytes)" -f $version.files[0].filename, $version.files[0].size)
if ($config.project.is_draft -and -not $PublishProject) {
    Write-Host ''
    Write-Host 'NOTE: the project was created as a DRAFT. Open the project page and publish it when you are happy'
    Write-Host '      with how it looks (or re-run with -PublishProject to request public visibility).'
}
