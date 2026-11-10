#requires -Version 7.0
param([string]$TargetRoot = [Environment]::GetFolderPath('UserProfile'))
$ErrorActionPreference = 'Stop'
$taskRules = [IO.File]::ReadAllText((Join-Path (Split-Path -Parent $PSScriptRoot) 'writing/session-instructions.md')).Trim()
$taskAgents = Join-Path ([IO.Path]::GetFullPath($TargetRoot)) '.codex/AGENTS.md'
$taskExisting = if (Test-Path -LiteralPath $taskAgents) { [IO.File]::ReadAllText($taskAgents) } else { '' }
$taskPattern = '(?s)<!-- work-agent:sessions:start -->.*?<!-- work-agent:sessions:end -->'
$taskNext = if ([regex]::IsMatch($taskExisting, $taskPattern)) { [regex]::Replace($taskExisting, $taskPattern, [Text.RegularExpressions.MatchEvaluator]{ param($match) $taskRules }) } else { $taskExisting.TrimEnd() + "`n`n" + $taskRules + "`n" }
if ($taskNext -ceq $taskExisting) { Write-Output 'Codex 작업 세션 이름 규칙이 이미 설치되어 있습니다.'; return }
New-Item -ItemType Directory -Path (Split-Path -Parent $taskAgents) -Force | Out-Null
if (Test-Path -LiteralPath $taskAgents) { Copy-Item -LiteralPath $taskAgents -Destination ($taskAgents + '.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
[IO.File]::WriteAllText($taskAgents, $taskNext, [Text.UTF8Encoding]::new($false))
Write-Output 'Codex 작업 세션 이름 규칙을 설치했습니다. 기존 개인 지침은 보존했습니다.'
