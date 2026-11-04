$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskUserJiraToken = [Environment]::GetEnvironmentVariable('JIRA_API_TOKEN', 'User')
if (-not [string]::IsNullOrWhiteSpace($taskUserJiraToken)) {
    $env:JIRA_API_TOKEN = $taskUserJiraToken
}
$taskJava = Get-Command java -ErrorAction Stop
$taskJar = Join-Path $PSScriptRoot 'work-agent-0.1.1.jar'
if (-not (Test-Path -LiteralPath $taskJar)) {
    & mvn -q package
    if ($LASTEXITCODE -ne 0) { throw '빌드에 실패했습니다.' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target/work-agent-0.1.1.jar') -Destination $taskJar
}
& $taskJava.Source '-Dfile.encoding=UTF-8' -jar $taskJar
