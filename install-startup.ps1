$ErrorActionPreference = 'Stop'
$taskName = 'WorkAgent-Recovery'
$taskLauncher = Join-Path $PSScriptRoot 'scripts/start-hidden.vbs'
$taskStarter = Join-Path $PSScriptRoot 'start.ps1'
$taskShell = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if (-not $taskShell) { $taskShell = Get-Command powershell.exe -ErrorAction Stop }
$taskAccount = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$taskExisting = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($taskExisting -and -not ($taskExisting.Actions.Arguments -like "*$taskLauncher*")) { throw '같은 이름의 다른 예약 작업이 있습니다.' }
$taskArguments = '"{0}" "{1}" "{2}"' -f $taskLauncher, $taskShell.Source, $taskStarter
$taskAction = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32/wscript.exe') -Argument $taskArguments -WorkingDirectory $PSScriptRoot
$taskRepeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$taskLogon = New-ScheduledTaskTrigger -AtLogOn -User $taskAccount
$taskPrincipal = New-ScheduledTaskPrincipal -UserId $taskAccount -LogonType Interactive -RunLevel Limited
$taskSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds 45) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger @($taskRepeat,$taskLogon) -Principal $taskPrincipal -Settings $taskSettings -Description '로그인 시 업무 에이전트를 실행하고, 종료되면 1분 간격으로 다시 실행합니다.' -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Write-Output 'WorkAgent-Recovery 자동 실행을 등록했습니다.'
