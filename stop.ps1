$ErrorActionPreference = 'Stop'
$taskWorkDirectory = Join-Path $PSScriptRoot 'work'
New-Item -ItemType Directory -Path $taskWorkDirectory -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $taskWorkDirectory 'stopped'), (Get-Date).ToString('o'))
$taskRecovery = Get-ScheduledTask -TaskName 'WorkAgent-Recovery' -ErrorAction SilentlyContinue
if ($taskRecovery -and ($taskRecovery.Actions.Arguments -like "*$PSScriptRoot*")) {
    Stop-ScheduledTask -TaskName 'WorkAgent-Recovery' -ErrorAction SilentlyContinue
}
$taskJar = Join-Path $PSScriptRoot 'work-agent-0.4.2.jar'
$taskPattern = '(?i)-jar\s+"?' + [regex]::Escape($taskJar) + '(?:"|\s|$)'
$taskProcesses = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match $taskPattern })
foreach ($taskProcess in $taskProcesses) {
    & taskkill.exe /PID $taskProcess.ProcessId /T /F | Out-Null
    if ($LASTEXITCODE -ne 0 -and (Get-Process -Id $taskProcess.ProcessId -ErrorAction SilentlyContinue)) { throw '업무 에이전트 프로세스를 중지하지 못했습니다.' }
}
Write-Output 'work-agent를 중지했습니다. 직접 start.ps1을 실행하기 전까지 자동 복구하지 않습니다.'
