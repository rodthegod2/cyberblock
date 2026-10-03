<#
.SYNOPSIS
    Installs Cyberblock into Cyberpunk 2077, or removes it again (-Remove). Windows PowerShell 5.1, no Python needed.

.DESCRIPTION
    Copies the game\ folder that sits next to this script into the Cyberpunk 2077 folder and records every file, with its
    sha256, in <game>\red4ext\plugins\Cyberblock\install-manifest.json. This is the same manifest tools\install.py writes,
    so either installer can remove what the other installed.

      - Refuses while Cyberpunk 2077 is running.
      - Finds the game through Steam's libraries (HKCU\Software\Valve\Steam and libraryfolders.vdf), or use -Game, or drop the
        game folder on install.bat.
      - Never overwrites a file it did not write itself (exit code 2, nothing changed). An existing ReShade.fxh is the one
        exception: other ReShade effects share it, so it is kept as it is and not recorded.
      - Before the very first install it zips your saves folder into %LOCALAPPDATA%\Cyberblock\save-backups.
      - Writes bin\x64\ReShadePreset.ini (turning the Cyberblock effect on) only when you have no preset yet.
      - Removes files an older version installed that this one no longer ships (unless you changed them).
      - -Remove deletes exactly what was installed; a file that changed since is left in place.
      - Warns, but goes on, when it does not see RED4ext, redscript, Codeware or ReShade.

.PARAMETER Game
    The Cyberpunk 2077 folder (the one that holds bin\x64\Cyberpunk2077.exe).
.PARAMETER Remove
    Remove what was installed.
.PARAMETER Payload
    The folder to copy (default: the game folder next to this script).
.PARAMETER Saves
    The saves folder to back up before the first install.
.PARAMETER Backups
    Where the saves backup zip goes.
.PARAMETER SteamPath
    Steam's folder (default: read from the registry).
.PARAMETER ProcessName
    The process name of the game that must not be running (default Cyberpunk2077).
#>
[CmdletBinding()]
param(
    [string]$Game,
    [switch]$Remove,
    [string]$Payload,
    [string]$Saves,
    [string]$Backups,
    [string]$SteamPath,
    [string]$ProcessName = 'Cyberpunk2077'
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

# defaults are set here, not in the param block: $PSScriptRoot is empty there in Windows PowerShell 5.1 under -File
if (-not $Payload) { $Payload = Join-Path $PSScriptRoot 'game' }
if (-not $Saves) { $Saves = Join-Path $env:USERPROFILE 'Saved Games\CD Projekt Red\Cyberpunk 2077' }
if (-not $Backups) { $Backups = Join-Path $env:LOCALAPPDATA 'Cyberblock\save-backups' }

$GameExe = 'bin/x64/Cyberpunk2077.exe'
$ManifestRel = 'red4ext/plugins/Cyberblock/install-manifest.json'
$HeaderRel = 'bin/x64/reshade-shaders/Shaders/ReShade.fxh'
$PresetRel = 'bin/x64/ReShadePreset.ini'
$PresetText = "Techniques=Cyberblock@Cyberblock.fx`n"
$script:GameRoot = ''

function Stop-Install([string]$Message, [int]$Code = 1) {
    $e = New-Object System.Exception($Message)
    $e.Data['code'] = $Code
    throw $e
}

function Get-FullPath([string]$Rel) {
    Join-Path $script:GameRoot ($Rel -replace '/', '\')
}

function Get-RelPath([string]$Full) {
    $Full.Substring($script:GameRoot.Length + 1).Replace('\', '/')
}

function Get-BytesSha([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace('-', '')).ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-FileSha([string]$Path) {
    Get-BytesSha ([System.IO.File]::ReadAllBytes($Path))
}

function Find-Game {
    $steam = $SteamPath
    if (-not $steam) {
        try { $steam = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -Name 'SteamPath').SteamPath }
        catch { Stop-Install 'Steam not found in the registry; pass -Game, or drop your Cyberpunk 2077 folder on install.bat' }
    }
    $vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
    try { $text = [System.IO.File]::ReadAllText($vdf) }
    catch { Stop-Install "cannot read Steam's libraries ($($_.Exception.Message)); pass -Game" }
    foreach ($m in [regex]::Matches($text, '"path"\s+"([^"]+)"')) {
        $library = $m.Groups[1].Value.Replace('\\', '\')
        $candidate = Join-Path $library 'steamapps\common\Cyberpunk 2077'
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\x64\Cyberpunk2077.exe') -PathType Leaf) { return $candidate }
    }
    Stop-Install 'Cyberpunk 2077 not found in the Steam libraries; pass -Game'
}

function Read-Manifest {
    $path = Get-FullPath $ManifestRel
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    $files = [ordered]@{}
    $dirs = @()
    try {
        $m = [System.IO.File]::ReadAllText($path) | ConvertFrom-Json
        if ($null -eq $m.files -or $null -eq $m.dirs) { throw 'files and dirs are required' }
        foreach ($p in $m.files.PSObject.Properties) { $files[$p.Name] = [string]$p.Value }
        $dirs = @($m.dirs | ForEach-Object { [string]$_ })
    }
    catch { Stop-Install "$path is unreadable ($($_.Exception.Message)); check it and delete it by hand" }
    $root = $script:GameRoot + '\'
    $outside = @(@($files.Keys) + $dirs | Where-Object {
            -not (([System.IO.Path]::GetFullPath((Get-FullPath $_)) + '\').StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) })
    if ($outside.Count -gt 0) { Stop-Install "$path lists paths outside the game folder, not trusting it: $($outside -join ', ')" }
    return @{ files = $files; dirs = $dirs }
}

function Save-Manifest($Entries, $Dirs) {
    $keys = [string[]]@($Entries.Keys)
    [Array]::Sort($keys, [System.StringComparer]::Ordinal)
    $files = [ordered]@{}
    foreach ($k in $keys) { $files[$k] = $Entries[$k] }
    $sortedDirs = [string[]]@($Dirs | Select-Object -Unique)
    [Array]::Sort($sortedDirs, [System.StringComparer]::Ordinal)
    $manifest = [ordered]@{ version = 1; files = $files; dirs = $sortedDirs }
    $json = (ConvertTo-Json -InputObject $manifest -Depth 5) + "`n"
    [System.IO.File]::WriteAllText((Get-FullPath $ManifestRel), $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Remove-Unchanged($Files) {
    # deletes the files whose content still matches the recorded sha256; returns those left because they changed
    $left = @()
    foreach ($rel in $Files.Keys) {
        $path = Get-FullPath $rel
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        if ((Get-FileSha $path) -eq $Files[$rel]) { Remove-Item -LiteralPath $path -Force } else { $left += $rel }
    }
    return , $left
}

function Remove-EmptyDirs($Dirs) {
    foreach ($rel in @($Dirs | Sort-Object { ($_ -split '/').Count } -Descending)) {
        try { [System.IO.Directory]::Delete((Get-FullPath $rel)) } catch { }  # not empty (something else lives there) or already gone
    }
}

function New-DirChain([string]$Path) {
    # mkdir -p; returns the game-relative paths that did not exist before
    $created = @()
    $p = $Path
    while ($p -and -not (Test-Path -LiteralPath $p)) { $created += $p; $p = Split-Path -Parent $p }
    if ($created.Count -gt 0) { New-Item -ItemType Directory -Force -Path $Path | Out-Null }
    return , @($created | ForEach-Object { Get-RelPath $_ })
}

function Backup-Saves {
    if (-not (Test-Path -LiteralPath $Saves -PathType Container)) { Write-Host "no saves folder at $Saves; skipping the backup"; return }
    New-Item -ItemType Directory -Force -Path $Backups | Out-Null
    $stamp = [DateTime]::UtcNow.ToString("yyyyMMdd'T'HHmmss") + 'Z'
    $zip = Join-Path $Backups "saves-$stamp.zip"
    for ($n = 2; Test-Path -LiteralPath $zip; $n++) { $zip = Join-Path $Backups "saves-$stamp-$n.zip" }  # never overwrite an earlier backup
    Write-Host "backing up $Saves ..."
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($Saves, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "saves backed up to $zip"
}

function Show-FrameworkWarnings {
    $checks = @(
        @('RED4ext', @('red4ext/RED4ext.dll')),
        @('redscript', @('engine/tools/scc.exe', 'engine/tools/scc_lib.dll')),
        @('Codeware', @('red4ext/plugins/Codeware/Codeware.dll')),
        @('ReShade', @('bin/x64/dxgi.dll', 'bin/x64/d3d12.dll', 'bin/x64/d3d11.dll'))
    )
    foreach ($c in $checks) {
        $found = @($c[1] | Where-Object { Test-Path -LiteralPath (Get-FullPath $_) -PathType Leaf })
        if ($found.Count -eq 0) {
            Write-Host ("warning: could not find $($c[0]) (looked for " + ($c[1] -join ', ') + '). Cyberblock needs it: install it first.')
        }
    }
}

function Install-Cyberblock {
    if (-not (Test-Path -LiteralPath (Get-FullPath $GameExe) -PathType Leaf)) {
        Stop-Install "$script:GameRoot is not a Cyberpunk 2077 folder ($GameExe is missing)"
    }
    if (-not (Test-Path -LiteralPath $Payload -PathType Container)) {
        Stop-Install "nothing installed, the game folder next to install.ps1 is missing ($Payload): unzip the whole release first"
    }
    $payloadRoot = (Resolve-Path -LiteralPath $Payload).Path.TrimEnd('\')
    $files = [ordered]@{}
    foreach ($f in (Get-ChildItem -LiteralPath $payloadRoot -Recurse -File | Sort-Object FullName)) {
        $files[$f.FullName.Substring($payloadRoot.Length + 1).Replace('\', '/')] = $f.FullName
    }
    if ($files.Count -eq 0) { Stop-Install "nothing installed, $payloadRoot is empty: unzip the whole release first" }

    $old = Read-Manifest
    $owned = if ($old) { $old.files } else { [ordered]@{} }
    if (-not $owned.Contains($HeaderRel) -and (Test-Path -LiteralPath (Get-FullPath $HeaderRel))) {
        Write-Host "kept the existing $HeaderRel (not ours: not touched, not recorded)"
        $files.Remove($HeaderRel)
    }
    $foreign = @($files.Keys | Where-Object { (Test-Path -LiteralPath (Get-FullPath $_)) -and -not $owned.Contains($_) })
    if ($foreign.Count -gt 0) {
        Stop-Install ("refusing to overwrite files Cyberblock did not install, nothing changed:`n  " + ($foreign -join "`n  ")) 2
    }
    Show-FrameworkWarnings
    if (-not $old) { Backup-Saves }

    $entries = [ordered]@{}
    foreach ($k in $owned.Keys) { if ($files.Contains($k) -or $k -eq $PresetRel) { $entries[$k] = $owned[$k] } }  # still ours if we fail midway
    $dirs = New-Object System.Collections.Generic.List[string]
    if ($old) { foreach ($d in $old.dirs) { $dirs.Add($d) } }
    try {
        $stale = [ordered]@{}
        foreach ($k in $owned.Keys) { if (-not $entries.Contains($k)) { $stale[$k] = $owned[$k] } }  # shipped by an earlier version only
        foreach ($rel in (Remove-Unchanged $stale)) { Write-Host "left ${rel}: it changed since it was installed" }
        foreach ($rel in $files.Keys) {
            $dst = Get-FullPath $rel
            foreach ($d in (New-DirChain (Split-Path -Parent $dst))) { $dirs.Add($d) }
            $bytes = [System.IO.File]::ReadAllBytes($files[$rel])
            [System.IO.File]::WriteAllBytes($dst, $bytes)
            $entries[$rel] = Get-BytesSha $bytes
        }
        $preset = Get-FullPath $PresetRel
        if (-not (Test-Path -LiteralPath $preset)) {  # never replace a preset the user (or ReShade) already has
            foreach ($d in (New-DirChain (Split-Path -Parent $preset))) { $dirs.Add($d) }
            $bytes = [System.Text.Encoding]::ASCII.GetBytes($PresetText)
            [System.IO.File]::WriteAllBytes($preset, $bytes)
            $entries[$PresetRel] = Get-BytesSha $bytes
        }
    }
    finally {
        if ($entries.Count -gt 0) {
            foreach ($d in (New-DirChain (Split-Path -Parent (Get-FullPath $ManifestRel)))) { $dirs.Add($d) }
            Save-Manifest $entries $dirs
        }
    }
    Write-Host "installed $($files.Count) files into $script:GameRoot"
}

function Uninstall-Cyberblock {
    $manifest = Read-Manifest
    if ($null -eq $manifest) {
        Write-Host "nothing to remove: no install manifest at $(Get-FullPath $ManifestRel)"
        return
    }
    $left = Remove-Unchanged $manifest.files
    Remove-Item -LiteralPath (Get-FullPath $ManifestRel) -Force
    Remove-EmptyDirs $manifest.dirs
    foreach ($rel in $left) { Write-Host "left ${rel}: it changed since it was installed" }
    Write-Host "removed $($manifest.files.Count - @($left).Count) files from $script:GameRoot"
}

try {
    if (Get-Process -Name $ProcessName -ErrorAction SilentlyContinue) {
        Stop-Install "Cyberpunk 2077 is running ($ProcessName): close it and run this again."
    }
    $found = if ($Game) { $Game } else { Find-Game }
    $script:GameRoot = [System.IO.Path]::GetFullPath($found).TrimEnd('\')
    if ($Remove) { Uninstall-Cyberblock } else { Install-Cyberblock }
}
catch {
    $code = 1
    if ($_.Exception.Data.Contains('code')) { $code = [int]$_.Exception.Data['code'] }
    [Console]::Error.WriteLine($_.Exception.Message)
    if (-not $_.Exception.Data.Contains('code')) { [Console]::Error.WriteLine('Is Cyberpunk 2077 still running? Close it and run this again.') }
    exit $code
}
exit 0
