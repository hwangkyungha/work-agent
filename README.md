# work-agent

Slack에서 요청하고, 로컬 웹페이지에서 업무를 조회하며, Jira에 워크로그를 등록하는 개인 업무 에이전트입니다. Java 21과 SQLite로 실행하며, 각자 에이전트 이름을 정해 사용할 수 있습니다.

## 준비 사항

- Java 21 이상과 Maven이 필요합니다.
- 자연어 요청을 사용하려면 Codex CLI 또는 Claude Code CLI를 설치하고 로그인해야 합니다.
- Slack, Jira, 캘린더는 사용할 기능에 맞춰 연결합니다. 연결하지 않아도 로컬 화면에서 할 일과 계획을 관리할 수 있습니다.

## 실행

저장소를 복제하고 개인 설정 파일을 준비하세요.

```powershell
git clone https://github.com/hwangkyungha/work-agent.git
cd work-agent
Copy-Item config.example.properties config.properties
```

`config.properties`를 편집한 뒤 다음 명령으로 실행하세요.

```powershell
./start.ps1
```

첫 실행 시 테스트와 빌드를 수행합니다. 브라우저에서 [work-agent](http://127.0.0.1:8765)을 여세요. 기본적으로 이 PC에서만 접속할 수 있습니다. 종료하려면 실행한 창에서 `Ctrl+C`를 누르세요.

소스를 수정했으면 실행 중인 프로그램을 종료한 뒤 `./build.ps1`로 테스트하고 다시 빌드하세요. 빌드에는 Maven과 의존성을 내려받을 인터넷 연결이 필요합니다.

## 연결 설정

`config.example.properties`를 복사한 `config.properties`에 개인 설정을 입력하세요. 같은 이름의 환경 변수 값이 우선합니다. Windows 파일 경로에는 `/`를 사용하세요. `config.properties`와 업무 데이터는 Git에 포함되지 않습니다.

### 내 에이전트 이름

저장소와 제품 이름은 `work-agent`입니다. 사용자는 로컬 `config.properties`에서 자신의 에이전트 이름과 부르는 말을 정할 수 있습니다.

```properties
AGENT_NAME=work-agent
AGENT_CALL_NAME=work-agent
```

`AGENT_NAME`은 화면과 대화에서 사용할 이름이고, `AGENT_CALL_NAME`은 요청 앞에서 부르는 말입니다. 예를 들어 이름을 `보리`, 부르는 말을 `보리야`로 정하면 `보리야, 브리핑`이라고 요청할 수 있습니다. 이름 없이 `브리핑`이라고 요청해도 동작합니다. 부르는 말만 입력하면 에이전트가 응답합니다. 변경 후에는 서버를 다시 실행하세요.

개인 이름은 로컬 설정에만 저장합니다. 공유용 예제 설정과 Slack 앱 manifest의 기본 이름은 `work-agent`입니다. Slack에 보이는 봇 이름도 바꾸려면 앱 생성 시 manifest의 `display_information.name`과 `features.bot_user.display_name`을 원하는 이름으로 변경하세요. 이미 만든 Slack 앱의 표시 이름은 Slack 앱 설정에서 변경해야 합니다.

### Slack

1. [Slack 앱 만들기](https://api.slack.com/apps)에서 **From a manifest**를 선택하고 `slack-app-manifest.json`을 입력하세요.
2. 앱을 사용할 워크스페이스에 설치하고 Bot User OAuth Token을 `SLACK_BOT_TOKEN`에 넣으세요.
3. Basic Information → App-Level Tokens에서 `connections:write` 권한을 가진 토큰을 만들고 `SLACK_APP_TOKEN`에 넣으세요.
4. 본인의 Slack 프로필에서 멤버 ID를 복사해 `SLACK_USER_ID`에 넣으세요.
5. 앱을 실행하고 봇에게 DM을 보내세요. 지정한 본인의 DM만 처리합니다.

공개 수신 주소 없이 Socket Mode로 연결합니다. 초기 버전은 개인용이며 공용 채널의 멘션이나 여러 사용자의 업무 분리를 지원하지 않습니다.

### Jira

`JIRA_EMAIL`에 자신의 Atlassian 계정 이메일을 입력하고, `JIRA_API_TOKEN` 환경변수에 API 토큰을 설정하세요. `JIRA_TOKEN_ENV=JIRA_API_TOKEN`은 토큰을 읽을 환경변수 이름을 지정합니다. 토큰 값을 설정 파일에 복사하지 않습니다. 지정한 환경변수가 비어 있어도 다른 토큰으로 대체하지 않습니다.

scope가 있는 Cloud 토큰은 `JIRA_KIND=cloud`로 설정하고, `JIRA_URL`에 `https://api.atlassian.com/ex/jira/{cloudId}`를 입력하세요. Cloud ID는 자신의 Jira 사이트의 `https://<사이트>.atlassian.net/_edge/tenant_info`에서 확인할 수 있습니다. Classic scope는 `read:jira-work`, `write:jira-work`, `read:jira-user`를 사용합니다. 토큰 scope와 별도로 자신의 Jira 계정에도 이슈 조회와 워크로그 등록 권한이 있어야 합니다.

`start.ps1`은 Windows 사용자 변수의 `JIRA_API_TOKEN`을 실행할 Java 프로세스에 전달하므로, 이미 열려 있는 Codex나 터미널에서도 새 사용자 변수를 사용할 수 있습니다.

| 설정 | 내용 |
|---|---|
| `JIRA_URL` | scope가 있는 Cloud 토큰은 `https://api.atlassian.com/ex/jira/{cloudId}`, 그 외에는 Jira 기본 주소입니다. |
| `JIRA_KIND` | Cloud는 `cloud`, 사내 Jira Data Center는 `server`입니다. |
| `JIRA_TOKEN_ENV` | 토큰 값을 읽을 환경변수 이름입니다. 현재 `JIRA_API_TOKEN`을 사용합니다. |
| `JIRA_EMAIL` | Cloud API token을 사용할 때 계정 이메일을 넣습니다. 비우면 Bearer 인증을 사용합니다. |
| `JIRA_JQL` | 내 업무를 가져올 검색 조건입니다. 기본값은 내 담당 미해결 이슈와 최근 수정 이슈입니다. |

워크로그는 인증한 계정으로 등록합니다. Jira의 시간 추적 기능과 해당 이슈에 시간을 등록할 권한이 필요합니다. 기본 하루 8시간을 사용자가 정한 비율로 나누며, 반차 등은 날짜별 기준 시간을 바꿀 수 있습니다. 에이전트가 CLI 기록 길이로 시간을 추정하지 않습니다.

등록 중 응답을 받지 못하면 결과를 **확인 필요**로 남깁니다. 재요청할 때 추적 문구가 있는 기존 Jira 기록을 찾아 중복 등록을 피합니다. 기존 등록을 확인하지 못하면 재등록하지 않습니다. 이 경우 Jira 기록을 직접 확인해야 합니다. 이미 등록한 날짜의 배분 수정과 Jira 기록 삭제는 초기 버전에서 지원하지 않습니다.

### Google Calendar

Google Calendar의 회의 일정은 먼저 읽기 전용으로 연결합니다.

자신의 iCal 주소를 로컬 `config.properties`의 `CALENDAR_ICS_URL`에 입력하세요. 이 이름의 환경변수를 새로 만들 필요는 없습니다. 형식은 다음과 같습니다.

```properties
CALENDAR_ICS_URL=https://calendar.google.com/calendar/ical/캘린더주소/basic.ics
```

공개 주소가 외부에서 조회되지 않으면 같은 캘린더의 비공개 iCal 주소로 바꿀 수 있습니다. 비공개 주소를 사용하는 절차는 다음과 같습니다.

1. 컴퓨터에서 [Google Calendar](https://calendar.google.com)를 열고 설정으로 들어가세요.
2. 왼쪽의 **내 캘린더의 설정**에서 업무 일정을 관리하는 캘린더를 선택하세요.
3. **캘린더 통합**에서 **iCal 형식의 비공개 주소**를 복사하세요.
4. 이 주소를 로컬 `config.properties`의 `CALENDAR_ICS_URL`에 입력하고 프로그램을 다시 실행하세요. 비공개 주소는 채팅이나 공유 문서에 올리지 마세요.

비공개 주소가 보이지 않으면 조직 관리자가 제공을 제한했을 수 있습니다. 이 경우에는 관리자에게 확인하거나 Google Calendar API를 통한 계정 연결을 추가해야 합니다. [Google 공식 안내](https://support.google.com/calendar/answer/37648?hl=ko)

`CALENDAR_ICS_URL`에 구독용 ICS 주소를, 또는 `CALENDAR_ICS_FILE`에 내보낸 ICS 파일 경로를 넣으세요. 반복 일정, 제외 날짜, 개별 일정 변경과 취소를 반영합니다. 조회 범위는 오늘 기준 이전 14일부터 이후 60일까지입니다.

현재는 읽기 전용 ICS 연결입니다. Slack이나 화면에서 새로 등록한 회의는 업무 에이전트의 로컬 일정에 저장하며, Google Calendar에는 쓰지 않습니다. Google Calendar에서 회의를 생성하거나 수정하는 기능은 계정 연결과 쓰기 권한을 사용하는 API 연동을 추가해야 합니다.

### 자연어 처리

기본값은 이미 로그인한 `codex` CLI입니다. `AGENT_ENGINE=claude`로 바꾸면 Claude CLI를 사용합니다. CLI 실행 파일을 찾지 못하면 `AGENT_EXECUTABLE`에 실행 파일 경로를 넣으세요. Windows의 npm Codex 설치는 `node`와 Codex 진입 스크립트를 통해 실행합니다.

Codex는 사용자 도구 설정을 읽지 않는 별도의 일회성 세션을 사용합니다. 사용자 설정에 있는 전용 모델 제공자나 프록시 설정은 그대로 상속하지 않습니다. Claude는 내장 도구와 MCP를 꺼 두고 요청을 해석합니다. 프로그램이 허용한 작업만 검증해 실행하며, AI가 임의 명령을 실행하지 않습니다. `AGENT_MODEL`을 비워 두면 CLI 기본 모델을 사용합니다.

`AGENT_ENGINE=off`로 두어도 웹페이지의 업무·계획·기록·워크로그 기능을 쓸 수 있습니다. `브리핑`, `데일리`, `이번 주 워크로그`라는 간단한 조회 요청도 AI 호출 없이 동작합니다. 자연어 처리는 해당 CLI 계정의 사용량을 소비합니다.

## 사용할 수 있는 기능

- **아침 브리핑:** 검토할 작업 결과, 마감이 임박하거나 막힌 업무, 회의, 오늘 계획 제안을 보여줍니다. 프로젝트별 완료 건수는 보여주지만 전체 프로젝트의 실제 진척률로 표현하지 않습니다.
- **오늘 계획:** 업무를 고르고 순서를 바꾸어 날짜별로 저장합니다. 중요도와 긴급도를 바꿀 수 있습니다.
- **데일리 자료:** 어제 활동과 오늘 계획을 모읍니다. 추가로 공유할 내용이 있을 때만 덧붙입니다. HTML 보고서를 열어 파일로 저장하거나 인쇄할 수 있습니다.
- **활동 기록:** 수동 입력과 Claude/Codex 기록을 함께 조회합니다. CLI 원본은 수정하지 않습니다.
- **워크로그:** 요일별 활동을 확인하고 Jira 이슈별 비율을 정해 초안으로 저장합니다. 하루씩 또는 주말에 여러 날짜를 이어서 처리할 수 있습니다. 실제 Jira 등록은 별도 요청이나 버튼으로 실행합니다.
- **팀원 기록:** 역할, 관찰, 칭찬, 피드백과 후속 조치를 남기고 1on1 자료를 만듭니다. 일반 브리핑과 데일리 자료에는 개인 관찰 내용을 넣지 않습니다.
- **팀 Jira 정리:** 팀원별 담당 이슈와 해당 주에 Jira에 등록된 시간을 조회합니다. 기록된 시간이 전체 근무 시간이라고 해석하거나 개인 평가를 자동으로 만들지 않습니다.
- **실행 결과 접수:** 외부에서 실행한 작업 결과, 결과물 위치, 검증 결과를 받아 검토 전후를 구분합니다.

### 요청 예시

```text
아침 브리핑
내일 10시에 LCT CI report 기능 리뷰가 있어. 일정에 추가해 줘.
CT-123 리뷰 준비를 내일 마감인 중요한 일로 등록해 줘.
리뷰 준비를 오늘 계획의 첫 번째로 옮겨 줘.
어제 CT-123 원인 분석을 했어. 활동에 기록해 줘.
데일리에 검증 환경 지원이 필요하다는 내용도 덧붙여 줘.
이번 주 워크로그
2026-11-04는 CT-123에 60%, CT-456에 40%로 배분 초안을 저장해 줘.
2026-11-04 워크로그를 Jira에 등록해 줘.
팀원 민수의 Jira 사용자명은 minsu이고 담당 역할은 LCT CI 개발이야. 등록해 줘.
민수가 장애 재현 절차를 정리해서 팀이 원인을 빨리 찾았어. 칭찬 기록을 남겨 줘.
민수의 1on1 준비 자료를 보여 줘.
```

### CLI 기록 수집

기본적으로 사용자 폴더의 `.codex/sessions`와 `.claude/projects`에서 최근 14일에 수정된 JSONL 파일을 읽습니다. `CODEX_LOG_ROOT`, `CLAUDE_LOG_ROOT`, `IMPORT_DAYS`로 범위를 지정할 수 있습니다. 파일별 읽은 위치를 저장하고, 아직 기록 중인 마지막 줄은 다음 수집까지 기다립니다.

대화 본문 중 사용자와 AI의 일반 텍스트만 수집하며 내부 추론과 도구 결과는 수집하지 않습니다. 날짜와 세션별 마지막 AI 응답을 활동 후보로 표시합니다. 같은 날짜·세션에 이슈가 하나만 등장하면 연결하고, 여러 이슈가 등장하면 임의로 하나를 고르지 않습니다. Slack에서 활동을 이슈에 연결할 수 있습니다. 후보는 작업 완료나 검증 성공을 증명하지 않으며 사용자가 데일리와 워크로그를 만들 때 확인해야 합니다.

원본은 원래 위치에 남고, 검색에 필요한 발췌와 출처를 SQLite에 보관합니다. 현재 구현은 JSONL 형식의 로컬 기록을 지원합니다. Codex의 다른 저장 형식이나 클라우드에만 있는 세션은 별도 연결이 필요합니다. 자연어 요청 시에는 최근 활동, 업무 목록, 최근 대화가 설정한 CLI에 전달됩니다. 전체 원본과 개인 관찰 기록은 일반 계획 요청의 문맥에 넣지 않습니다.

### 정기 실행

프로그램이 켜져 있는 동안 5분 간격으로 새 기록을 확인합니다. Slack 연결 설정이 있으면 평일 오전 9시에 브리핑, 금요일 오후 4시 30분에 주간 워크로그 정리 자료를 보냅니다. 금요일 오후 4시 이후 팀원별 Jira 정리도 수행합니다. `BRIEFING_TIME`과 `FRIDAY_REVIEW_TIME`은 바꿀 수 있고, 빈 값이면 해당 알림을 끕니다.

시간대는 설정 옵션 없이 **Asia/Seoul**로 고정합니다. 날짜별 기록, 이번 주 범위(월요일부터 일요일), 예약 알림과 Jira 작업 시작 날짜를 모두 한국 시간으로 계산합니다. 늦게 켜진 프로그램은 예정 시각에서 한 시간 이상 지난 알림을 뒤늦게 보내지 않습니다. 컴퓨터가 꺼져 있으면 수집과 알림도 멈춥니다.

## 외부 작업 실행기 연결

이 버전은 실행 결과를 접수하고 검토 상태를 관리합니다. 개발 작업을 자율적으로 실행하거나 테스트 성공을 자체 확인하는 실행기는 포함하지 않습니다. 기존 Claude/Codex 작업을 마친 뒤 아래 API로 결과를 전달할 수 있습니다.

`config.properties`의 `WORK_AGENT_TOKEN`에 직접 정한 긴 문자열을 넣고 서버를 다시 시작하세요. 외부 실행기에서 환경 변수로 같은 값을 사용해 다음과 같이 호출할 수 있습니다.

```powershell
$taskBody = @{
    title = 'LCT CI report 수정'
    jiraKey = 'CT-123'
    result = '수정한 결과를 검토해야 합니다.'
    artifact = 'D:/Git/lct의 커밋 또는 결과물 위치'
    verification = '실행기가 실제 확인한 테스트 결과'
    reviewed = $false
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8765/api/jobs' `
    -Headers @{ 'X-Work-Agent-Token' = $env:WORK_AGENT_TOKEN } `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($taskBody))
```

## 데이터와 검증

기본 저장 위치는 이 폴더의 `work/data/work-agent.db`입니다. 프로그램을 종료한 뒤 `work/data` 폴더를 복사하면 기록을 백업할 수 있습니다. 인증 정보가 있는 `config.properties`와 개인 기록이 있는 `work/`는 소스 관리에서 제외합니다.

```powershell
mvn test
```

테스트는 한국 시간의 날짜 경계, 회의와 마감의 구분, 데일리 추가 내용, 비율 검증과 시간 합계, Jira 중복 등록 방지, 불확실한 응답 처리, 기록 수집 재개, 반복 일정 변경·취소, 관찰 기록 분리를 확인합니다. 실제 Slack과 Jira 연결은 각 계정의 설정을 넣은 뒤 확인해야 합니다.

구현에 참고한 공식 문서: [Codex CLI](https://learn.chatgpt.com/docs/developer-commands?surface=cli), [Claude CLI](https://code.claude.com/docs/en/headless), [Slack Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/), [Jira Cloud 워크로그](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-worklogs/), [Jira Data Center API](https://developer.atlassian.com/server/jira/platform/rest/v10000/api-group-issue/), [iCal4j 반복 일정](https://www.ical4j.org/examples/recur/).
