#requires -Version 7.0
param(
    [ValidateSet('Project','User')][string]$Scope = 'Project',
    [string]$TargetRoot = '',
    [switch]$ActivateStyle,
    [switch]$MigrateLegacy,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (-not $TargetRoot) { $TargetRoot = if ($Scope -eq 'User') { [Environment]::GetFolderPath('UserProfile') } else { $sourceRoot } }
$target = [IO.Path]::GetFullPath($TargetRoot)
$manifestPath = Join-Path $target '.work-agent/writing-install.json'
$prior = if (Test-Path -LiteralPath $manifestPath) { Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json } else { $null }
$expected = @{}
if ($prior) { foreach ($file in $prior.files) { $expected[$file.path] = $file.hash } }
$planned = [Collections.Generic.List[object]]::new()
$conflicts = [Collections.Generic.List[string]]::new()
$legacyPaths = [Collections.Generic.List[string]]::new()
$utf8 = [Text.UTF8Encoding]::new($false)
function Full-Target([string]$relative) {
    $path = [IO.Path]::GetFullPath((Join-Path $target $relative))
    if (-not $path.StartsWith($target.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '설치 경로가 대상 폴더 밖입니다.' }
    $parent = Split-Path -Parent $path
    while ($parent -and $parent.Length -ge $target.Length) {
        $item = Get-Item -LiteralPath $parent -Force -ErrorAction SilentlyContinue
        if ($item -and $item.LinkType) { throw "다른 위치를 가리키는 폴더 안에는 설치하지 않습니다: $parent" }
        if ($parent -eq $target) { break }; $parent = Split-Path -Parent $parent
    }
    return $path
}
function Hash-Text([string]$text) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($text))) }
function Plan-File([string]$relative, [string]$text, [switch]$Merge) {
    $path = Full-Target $relative
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if ($item -and $item.LinkType) { throw "연결된 파일을 자동으로 바꾸지 않습니다: $path" }
    $old = if (Test-Path -LiteralPath $path -PathType Leaf) { [IO.File]::ReadAllText($path) } else { $null }
    if ($null -ne $old -and $old -ne $text -and -not $Merge) {
        if (-not $expected.ContainsKey($relative) -or (Hash-Text $old) -ne $expected[$relative]) { $conflicts.Add($relative) }
    }
    $planned.Add([pscustomobject]@{ path=$relative; text=$text; changed=($old -cne $text) })
}
function Remove-LegacySections([string]$text) {
    $text = $text.Replace("`r`n", "`n")
    $legacy = [regex]::Match($text, '(?ms)^# 업무 메시지와 PR 리뷰 댓글\n.*?(?=^# |\z)')
    if ($legacy.Success) {
        if ($legacy.Value.Trim() -ne $legacySection.Trim()) { throw '기존 업무 글쓰기 항목이 달라 자동으로 옮기지 않았습니다.' }
        $text = $text.Remove($legacy.Index, $legacy.Length)
    }
    $korean = [regex]::Match($text, '(?ms)^# 한국어 출력 지침\n.*?(?=^# |\z)')
    if ($korean.Success) {
        if ($korean.Value.Trim() -ne $originalKorean.Trim()) { throw '기존 한국어 지침이 달라 자동으로 옮기지 않았습니다.' }
        $text = $text.Remove($korean.Index, $korean.Length)
    }
    return $text.TrimEnd()
}
$common = [IO.File]::ReadAllText((Join-Path $sourceRoot 'skills/work-agent-writing/common.md')).Replace("`r`n", "`n")
$originalKorean = ([regex]::Split($common, '(?m)^# 업무 글의 사실과 근거')[0]).TrimEnd()
$legacySection = @'
# 업무 메시지와 PR 리뷰 댓글

- 한국어 출력 지침을 응답뿐 아니라 사람이 읽는 공유 문서, Slack 메시지, Jira·PR 리뷰 댓글에도 적용한다. 코드 주석·인용·코드 식별자는 원래 표기를 보존한다.
- 업무 메시지와 PR 리뷰 댓글을 작성·수정할 때는 `polish-message`, 공유·보고 문서는 `ct-doc`의 독자·근거 기준을 적용한다.
- 원문의 수치·날짜·부정·예외 조건과 확신 정도를 보존한다. 조건이나 원인을 빠뜨려 문장 길이만 줄이지 않는다.
- 리뷰 댓글에는 확인한 조건과 동작, 영향을 먼저 적고 필요한 수정을 요청한다. 코드로 확인하지 못한 위험은 결함으로 단정하지 않는다.
'@
foreach ($agent in @('.agents','.claude')) {
    foreach ($skill in @('write-message','write-document','work-agent-writing')) {
        $destination = Full-Target "$agent/skills/$skill"
        if ((Test-Path -LiteralPath $destination) -and (Get-Item -LiteralPath $destination -Force).LinkType) { throw "기존 연결을 자동으로 덮어쓰지 않습니다: $destination" }
        $source = Join-Path $sourceRoot "skills/$skill"
        foreach ($file in Get-ChildItem -LiteralPath $source -Recurse -File) {
            $relative = $file.FullName.Substring($source.Length + 1).Replace('\','/')
            Plan-File "$agent/skills/$skill/$relative" ([IO.File]::ReadAllText($file.FullName))
        }
    }
}
$style = [IO.File]::ReadAllText((Join-Path $sourceRoot 'writing/claude-style.md')).Replace('{{COMMON}}', $common)
Plan-File '.claude/output-styles/work-agent-korean.md' $style
$agentsRelative = if ($Scope -eq 'User') { '.codex/AGENTS.md' } else { 'AGENTS.md' }
$agentsPath = Full-Target $agentsRelative
$agents = if (Test-Path -LiteralPath $agentsPath) { [IO.File]::ReadAllText($agentsPath) } else { '' }
if ($MigrateLegacy) { $agents = Remove-LegacySections $agents }
$commonPath = if ($Scope -eq 'User') { (Full-Target '.agents/skills/work-agent-writing/common.md').Replace('\','/') } else { '.agents/skills/work-agent-writing/common.md' }
$block = [IO.File]::ReadAllText((Join-Path $sourceRoot 'writing/codex-instructions.md')).Replace('{{COMMON_PATH}}', $commonPath).Trim()
$pattern = '(?s)<!-- work-agent:writing:start -->.*?<!-- work-agent:writing:end -->'
$existingBlock = [regex]::Match($agents, $pattern)
if ($existingBlock.Success) { $agents = $agents.Remove($existingBlock.Index, $existingBlock.Length).Insert($existingBlock.Index, $block) }
else { $agents = $agents.TrimEnd() + $(if ($agents.Trim()) { "`n`n" } else { '' }) + $block + "`n" }
Plan-File $agentsRelative ($agents.TrimEnd() + "`n") -Merge
$settingsRelative = if ($Scope -eq 'User') { '.claude/settings.json' } else { '.claude/settings.local.json' }
if ($ActivateStyle) {
    $settingsPath = Full-Target $settingsRelative
    $settings = if (Test-Path -LiteralPath $settingsPath) { Get-Content -LiteralPath $settingsPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable } else { @{} }
    $settings['outputStyle'] = 'work-agent-korean'
    Plan-File $settingsRelative (($settings | ConvertTo-Json -Depth 100) + "`n") -Merge
}
if ($MigrateLegacy) {
    $claudeRelative = '.claude/CLAUDE.md'
    $claudePath = Full-Target $claudeRelative
    if (Test-Path -LiteralPath $claudePath) { Plan-File $claudeRelative ((Remove-LegacySections ([IO.File]::ReadAllText($claudePath))) + "`n") -Merge }
    foreach ($root in @('.agents/skills','.codex/skills','.claude/skills','agent-skills/global')) {
        foreach ($name in @('polish-message','ct-doc')) {
            $path = Full-Target "$root/$name"
            if (Test-Path -LiteralPath $path) { $legacyPaths.Add($path) }
        }
    }
}
if ($conflicts.Count) { throw ('직접 수정한 파일을 보존했습니다: ' + ($conflicts -join ', ')) }
if ($Check) {
    $changed = @($planned | Where-Object changed | ForEach-Object path)
    [pscustomobject]@{ok=($changed.Count -eq 0 -and $legacyPaths.Count -eq 0);scope=$Scope;differences=$changed;legacyCount=$legacyPaths.Count} | ConvertTo-Json -Depth 5
    if ($changed.Count -or $legacyPaths.Count) { exit 1 }; exit 0
}
$backup = Full-Target ('.work-agent/backups/writing-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$changes = @($planned | Where-Object changed)
if ($changes.Count -or $legacyPaths.Count) { New-Item -ItemType Directory -Path $backup -Force | Out-Null }
foreach ($file in $changes) {
    $path = Full-Target $file.path
    if (Test-Path -LiteralPath $path) {
        $copy = Join-Path $backup $file.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
        Copy-Item -LiteralPath $path -Destination $copy
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, $file.text, $utf8)
}
foreach ($path in $legacyPaths) {
    # Each target was validated under the selected root before any move or link removal.
    $relative = $path.Substring($target.Length + 1)
    $copy = Join-Path $backup $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
    $item = Get-Item -LiteralPath $path -Force
    if ($item.LinkType) { [IO.File]::WriteAllText($copy + '.link.txt', [string]$item.Target, $utf8); Remove-Item -LiteralPath $path }
    else { Move-Item -LiteralPath $path -Destination $copy }
}
$owned = @($planned | Where-Object { $_.path -notin @($agentsRelative,$settingsRelative,'.claude/CLAUDE.md') } | ForEach-Object { @{path=$_.path;hash=(Hash-Text $_.text)} })
New-Item -ItemType Directory -Path (Split-Path -Parent $manifestPath) -Force | Out-Null
[IO.File]::WriteAllText($manifestPath, (@{version=1;scope=$Scope;files=$owned} | ConvertTo-Json -Depth 6), $utf8)
[pscustomobject]@{ok=$true;scope=$Scope;changed=$changes.Count;retired=$legacyPaths.Count;backup=$(if ($changes.Count -or $legacyPaths.Count) {$backup} else {''})} | ConvertTo-Json
