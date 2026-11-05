$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& mvn -q package
if ($LASTEXITCODE -ne 0) { throw '빌드 또는 테스트에 실패했습니다.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target/work-agent-0.3.2.jar') -Destination (Join-Path $PSScriptRoot 'work-agent-0.3.2.jar')
Write-Output '검증과 빌드를 마쳤습니다. ./start.ps1로 실행하세요.'
