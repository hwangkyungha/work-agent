param([switch]$Background)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskUserJiraToken = [Environment]::GetEnvironmentVariable('JIRA_API_TOKEN', 'User')
if (-not [string]::IsNullOrWhiteSpace($taskUserJiraToken)) {
    $env:JIRA_API_TOKEN = $taskUserJiraToken
}
$taskJava = Get-Command java -ErrorAction Stop
$taskJar = Join-Path $PSScriptRoot 'work-agent-0.3.2.jar'
if (-not (Test-Path -LiteralPath $taskJar)) {
    & mvn -q package
    if ($LASTEXITCODE -ne 0) { throw '빌드에 실패했습니다.' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target/work-agent-0.3.2.jar') -Destination $taskJar
}
if ($Background) {
    $taskPort = 8765
    foreach ($taskLine in Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.properties') -Encoding utf8) {
        if ($taskLine -match '^\s*PORT\s*=\s*(\d+)\s*$') { $taskPort = [int]$Matches[1] }
    }
    if ($env:PORT) { $taskPort = [int]$env:PORT }
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { return }
    $taskLogDirectory = Join-Path $PSScriptRoot 'work/logs'
    New-Item -ItemType Directory -Force -Path $taskLogDirectory | Out-Null
    Start-Process -FilePath $taskJava.Source -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"{0}"' -f $taskJar)) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLogDirectory 'server.out.log') -RedirectStandardError (Join-Path $taskLogDirectory 'server.err.log') | Out-Null
    return
}
& $taskJava.Source '-Dfile.encoding=UTF-8' -jar $taskJar
