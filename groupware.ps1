param([ValidateSet('setup', 'login', 'status', 'calendar-login', 'calendar-status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
foreach ($taskName in @('GROUPWARE_CONFIG')) {
    $taskValue = [Environment]::GetEnvironmentVariable($taskName, 'User')
    if ($taskValue -and -not [Environment]::GetEnvironmentVariable($taskName, 'Process')) {
        [Environment]::SetEnvironmentVariable($taskName, $taskValue, 'Process')
    }
}
$taskJar = Join-Path $PSScriptRoot 'work-agent-0.4.0.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw './build.ps1로 먼저 빌드하세요.' }
$taskCommand = if ($Action.StartsWith('calendar-')) { '--' + $Action } else { '--groupware-' + $Action }
& java '-Dfile.encoding=UTF-8' -jar $taskJar $taskCommand
if ($LASTEXITCODE -ne 0) { throw '그룹웨어 연결을 확인하지 못했습니다. 위 안내를 확인하세요.' }
