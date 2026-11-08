# 글쓰기 스킬 설치와 평가

공통 기준의 원본은 [common.md](../skills/work-agent-writing/common.md)입니다. 기존 한국어 지침 전문과 업무 글의 사실 보존 기준을 함께 관리합니다. [write-message](../skills/write-message/SKILL.md)는 메시지와 PR 댓글, [write-document](../skills/write-document/SKILL.md)는 공유 문서의 절차를 정합니다. 개발용 `ct-spec`의 설치 여부와 관계없이 사용할 수 있습니다.

## CLI에 설치

PowerShell 7에서 저장소 루트를 기준으로 실행합니다. 기본 범위는 이 프로젝트입니다.

```powershell
pwsh ./scripts/install-writing.ps1 -Scope Project -ActivateStyle
```

Codex의 `.agents/skills`와 Claude의 `.claude/skills`에 두 스킬과 공통 기준을 설치합니다. Claude 출력 스타일은 `.claude/output-styles/work-agent-korean.md`에 생성합니다. `-ActivateStyle`은 프로젝트의 `.claude/settings.local.json`에서 이 스타일을 선택합니다. 옵션을 빼면 현재 선택을 유지합니다. 코딩 지침을 유지하도록 스타일에 `keep-coding-instructions: true`를 넣습니다.

여러 프로젝트에서 사용할 개인 설치는 다음과 같습니다.

```powershell
pwsh ./scripts/install-writing.ps1 -Scope User -ActivateStyle
pwsh ./scripts/install-writing.ps1 -Scope User -ActivateStyle -Check
```

개인 설치는 사용자 폴더의 같은 스킬·스타일 경로와 `.codex/AGENTS.md`, `.claude/settings.json`을 사용합니다. Codex 지침에는 공통 기준을 읽는 표시 구간만 추가하거나 갱신합니다. 기존 지침과 Claude 설정의 다른 값은 보존합니다. 파일을 만든 뒤 Codex와 Claude를 다시 시작해 스킬과 스타일을 새로 읽으세요.

`-Check`는 파일을 바꾸지 않고 저장소 원본과 설치 상태를 비교합니다. 변경이 필요하면 종료 코드 1을 반환합니다. 업데이트는 같은 설치 명령을 다시 실행합니다. 도구가 설치한 파일을 사용자가 직접 수정했다면 덮어쓰기를 중단합니다. 개인 선호는 설치 파일을 고치기보다 프로젝트 지침의 표시 구간 밖에 추가하세요.

대상 폴더를 따로 지정하려면 `-TargetRoot <폴더>`를 사용합니다. 설치 기록과 교체 전 백업은 대상 폴더의 `.work-agent/`에 남습니다. 백업에는 기존 개인 설정이 들어갈 수 있으므로 Git에 포함하지 마세요.

## 이전 이름에서 이동

`polish-message`, `ct-doc`를 사용하던 개인 설치는 다음 명령으로 옮깁니다.

```powershell
pwsh ./scripts/install-writing.ps1 -Scope User -ActivateStyle -MigrateLegacy
```

기존 두 스킬과 연결은 백업으로 옮기고 새 이름만 설치합니다. 기존에 알려진 한국어 전문과 업무 글쓰기 항목은 공통 기준의 연결로 바꿉니다. 해당 항목이 예상한 내용과 다르면 자동으로 옮기지 않습니다. 다른 개인 지침과 기존 출력 스타일 파일은 보존합니다.

이 도구는 다른 저장소가 관리하는 `ct-spec`을 수정하지 않습니다. 직접 수정했던 개발용 스킬은 그 스킬의 원본과 대조해 별도로 정리해야 합니다. work-agent의 두 스킬에는 `ct-spec` 참조가 없습니다.

## work-agent에서 사용

CLI 설치 없이도 실행 파일에 포함된 공통 기준과 스킬을 사용합니다. 예를 들어 다음과 같이 요청할 수 있습니다.

- “이 문장을 Slack으로 보낼 메시지로 다듬어 줘.”
- “이번 주 활동과 등록 시간을 바탕으로 팀 주간 공유자료를 써 줘.”
- “이 자료로 2주 시험 적용 검토를 요청하는 문서를 써 줘.”

원래 요청을 글쓰기 단계에 그대로 전달합니다. 공유할 자료는 사용자가 지정한 데일리·주간 활동 또는 특정 팀원 면담 자료에서 읽습니다. 메시지 작성은 Jira 등록이나 외부 게시로 처리하지 않습니다. PR 리뷰 댓글은 기존 비공개 초안 저장 절차를 따릅니다.

중요한 계획·의사결정 문서는 별도 호출에 문서와 독자 정보만 주어 읽는 사람의 관점으로 검토합니다. 최대 두 번 수정한 뒤 남는 확인 사항은 응답에 표시합니다. 짧은 데일리와 일반 메시지는 이 절차를 추가하지 않습니다.

## 반복 평가

```powershell
pwsh ./scripts/evaluate-writing.ps1 -Repeats 3
```

평가에는 로그인한 Codex 또는 Claude CLI가 필요하며 모델 사용량이 발생합니다. 출력 비교는 work-agent에 설정한 CLI를 사용하고, 스킬 선택은 Claude CLI에서 별도로 확인합니다. 외부 게시나 실제 업무 데이터 변경은 수행하지 않습니다. 결과와 원본 응답은 기본적으로 `work/writing-evals/`에 저장합니다.

| 평가 | 확인하는 내용 |
|---|---|
| 출력 비교 | 공통 기준만 넣은 출력과 작업별 스킬도 넣은 출력을 같은 사례로 비교 |
| 원문 대조 | 이슈 번호·시간·날짜 보존과 조건·확신 정도를 검사 |
| 별도 판정 | 어느 쪽이 스킬 적용본인지 밝히지 않고 A/B 순서를 번갈아 평가 |
| 자동 선택 | 메시지·문서 요청의 선택과 커밋·코드 주석·PR 본문·할 일 요청의 과잉 선택 검사 |

`-Mode Output` 또는 `-Mode Triggers`로 나누어 실행할 수 있습니다. 자동 선택 평가에서는 먼저 두 스킬이 실제로 로드됐는지 확인합니다. CLI 오류와 스킬 미로드는 품질 실패와 구분해 `ERROR`로 기록합니다. 출력 평가는 최종 글을 비교하며 스킬의 전체 수행 절차까지 증명하지는 않습니다. 독자 검토와 설치 보존 동작은 별도 실행과 자동 테스트로 확인합니다.

이 평가는 소규모 반복 점검입니다. 모델의 문장 품질이나 사실 판단을 보장하지 않으며, 실패하면 원문과 응답을 읽어 원인을 확인한 뒤 같은 사례를 다시 실행합니다. 사례와 실행기는 저장소에서 함께 관리합니다.
