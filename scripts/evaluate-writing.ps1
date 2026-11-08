#requires -Version 7.0
param(
    [ValidateSet('Output','Triggers','All')][string]$Mode = 'All',
    [ValidateRange(1,10)][int]$Repeats = 3,
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('work/writing-evals/' + (Get-Date -Format yyyyMMdd-HHmmss)) }
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
if ($Mode -in @('All','Output')) {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw '평가용 빌드에 실패했습니다.' }
    [xml]$project = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
    $jar = Join-Path $repo ('target/work-agent-' + $project.project.version + '.jar')
    $separator = [IO.Path]::PathSeparator
    $classpath = (Join-Path $repo 'target/test-classes') + $separator + (Join-Path $repo 'target/classes') + $separator + $jar
    & java '-Dfile.encoding=UTF-8' -cp $classpath local.work.WritingEvaluation $output $Repeats
    if ($LASTEXITCODE -ne 0) { throw '출력 평가를 통과하지 못했습니다. 결과 파일을 확인하세요.' }
}
if ($Mode -in @('All','Triggers')) {
    $plugin = Join-Path $output 'plugin'
    New-Item -ItemType Directory -Path (Join-Path $plugin '.claude-plugin') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $plugin '.claude-plugin/plugin.json'), '{"name":"writing-eval","version":"0.0.1","description":"Isolated writing evaluation"}', $utf8)
    Copy-Item -LiteralPath (Join-Path $repo 'skills') -Destination $plugin -Recurse -Force
    $cases = (Get-Content -LiteralPath 'evals/writing/cases.json' -Raw -Encoding utf8 | ConvertFrom-Json).triggers
    $results = [Collections.Generic.List[object]]::new()
    $claude = (Get-Command claude -ErrorAction Stop).Source
    for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        foreach ($case in $cases) {
            $start = [Diagnostics.ProcessStartInfo]::new($claude)
            $start.WorkingDirectory = $output
            $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
            $start.StandardInputEncoding = $utf8; $start.StandardOutputEncoding = $utf8; $start.StandardErrorEncoding = $utf8
            foreach ($arg in @('-p','--input-format','text','--output-format','stream-json','--verbose','--setting-sources','','--strict-mcp-config','--tools','Skill','--allowedTools','Skill','--plugin-dir',$plugin,'--max-turns','2','--no-session-persistence')) { $start.ArgumentList.Add($arg) }
            foreach ($secret in @('JIRA_API_TOKEN','BITBUCKET_API_TOKEN','SLACK_BOT_TOKEN','SLACK_APP_TOKEN','WORK_AGENT_TOKEN','CALENDAR_ICS_URL')) { $start.Environment.Remove($secret) | Out-Null }
            $process = [Diagnostics.Process]::Start($start)
            try {
                $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
                $process.StandardInput.WriteLine($case.prompt); $process.StandardInput.Close()
                if (-not $process.WaitForExit(180000)) { $process.Kill($true); throw '스킬 선택 평가 시간이 초과됐습니다.' }
                $trace = $stdout.GetAwaiter().GetResult(); $diagnostic = $stderr.GetAwaiter().GetResult()
                [IO.File]::WriteAllText((Join-Path $output ("trigger-$repeat-" + $case.id + '.jsonl')), $trace, $utf8)
                $events = @($trace -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
                $init = $events | Where-Object { $_.type -eq 'system' -and $_.subtype -eq 'init' } | Select-Object -First 1
                $available = @($init.skills)
                $fired = @($events | Where-Object type -eq 'assistant' | ForEach-Object { $_.message.content } | Where-Object { $_.type -eq 'tool_use' -and $_.name -eq 'Skill' } | ForEach-Object { $_.input.skill } | Where-Object { $_ -like 'writing-eval:*' } | Select-Object -Unique)
                $result = $events | Where-Object type -eq 'result' | Select-Object -Last 1
                $expected = if ($case.expected) { 'writing-eval:' + $case.expected } else { $null }
                $status = if ($process.ExitCode -ne 0 -or $result.is_error -or $available -notcontains 'writing-eval:write-message' -or $available -notcontains 'writing-eval:write-document') { 'ERROR' } elseif (($expected -and $fired.Count -eq 1 -and $fired[0] -eq $expected) -or (-not $expected -and $fired.Count -eq 0)) { 'PASS' } else { 'FAIL' }
                $results.Add(@{repeat=$repeat;id=$case.id;status=$status;expected=$expected;fired=$fired})
                Write-Output ("$status $repeat " + $case.id)
            } finally { if (-not $process.HasExited) { $process.Kill($true) }; $process.Dispose() }
        }
    }
    $summary = @{repeats=$Repeats;cases=$cases.Count;results=$results;failures=@($results | Where-Object status -eq 'FAIL').Count;errors=@($results | Where-Object status -eq 'ERROR').Count}
    [IO.File]::WriteAllText((Join-Path $output 'trigger-summary.json'), ($summary | ConvertTo-Json -Depth 8), $utf8)
    if ($summary.failures -or $summary.errors) { throw '스킬 선택 평가를 통과하지 못했습니다. 결과 파일을 확인하세요.' }
}
Write-Output "평가 완료: $output"
