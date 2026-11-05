'use strict';
const $ = (s, root = document) => root.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
let state, settings, team, view = 'today', filter = 'open', planIds = [], selectedMember = '', worklogDay = '';
let noticeTimer, projectFilter='', taskSearch='', taskSort='priority', todayStatus='open', draggedId='', savingPlan=false, dragOriginY=0, didDrag=false;
const selectedTasks=new Set();
const sourceName = source => ({claude:'Claude',codex:'Codex',manual:'직접',calendar:'캘린더'}[source] || source);
const statusName = status => ({'todo':'todo','in-progress':'in-progress','done':'done'}[status] || status);
const logStatus = status => ({draft:'초안',posted:'Jira 등록 완료',posting:'등록 결과 확인 필요',unknown:'등록 결과 확인 필요',failed:'등록 실패'}[status] || '미작성');
const hours = seconds => Number((seconds / 3600).toFixed(2)).toString();
const clock = value => new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(value));
function dayAdd(day, n) {const d = new Date(day + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0,10);}
function monday(day) {return dayAdd(day, -(new Date(day + 'T00:00:00Z').getUTCDay() + 6) % 7);}
function weekday(day) {return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',weekday:'short'}).format(new Date(day+'T12:00:00+09:00'));}
function shortDay(day) {return `${Number(day.slice(5,7))}월 ${Number(day.slice(8,10))}일 (${weekday(day)})`;}
function notice(text) {const el=$('#notice');el.textContent=text;el.hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>el.hidden=true,6500);}
async function api(path, body) {
  const response=await fetch(path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  const data=await response.json(); if(!response.ok) throw new Error(data.error||'요청을 완료하지 못했습니다.'); return data;
}
function empty(title,text='',button='') {return `<div class="empty"><strong>${esc(title)}</strong>${esc(text)}${button}</div>`;}
function heading(title,description,actions='') {return `<div class="page-heading"><div><h1>${esc(title)}</h1><p>${esc(description)}</p></div><div class="heading-actions">${actions}</div></div>`;}
function issueLink(key,url='') {
  if(!key)return '';
  const source=url||state?.jiraIssues?.find(i=>i.id===key)?.issueUrl||state?.tasks?.find(t=>t.jiraKey===key)?.issueUrl;
  try {const target=new URL(source);if(!['http:','https:'].includes(target.protocol))return esc(key);return `<a href="${esc(target.href)}" target="_blank" rel="noopener noreferrer">${esc(key)}</a>`;}catch{return esc(key);}
}
function taskRow(task,index,controls='') {
  const linked=!!task.jiraKey,createdSort=(view==='today'?state.planSort:taskSort)?.startsWith('created-');
  const options=linked?`<option value="">${esc(task.jiraStatus||statusName(task.status))}</option>`:['todo','in-progress','done'].map(v=>`<option value="${v}" ${task.status===v?'selected':''}>${v}</option>`).join('');
  return `<div class="task-row ${task.status==='done'?'done':''}"><div class="task-detail"><div class="task-heading"><button type="button" class="task-title" data-edit-task="${esc(task.id)}" aria-label="${esc(task.title)} 수정">${esc(task.title)}</button><select class="task-status status-${esc(task.status)}" data-task-status="${esc(task.id)}" data-remote="${linked}" aria-label="${esc(task.title)} 상태" title="${esc(task.jiraStatus||statusName(task.status))}" ${task.hidden?'disabled':''}>${options}</select></div><small><span>${esc(task.product||'미분류')}</span>${issueLink(task.jiraKey,task.issueUrl)}${task.due?`<span class="${task.due<state.date?'error':''}">마감 ${esc(task.due)}</span>`:''}${createdSort&&task.createdAt?`<span>생성 ${esc(new Date(task.createdAt).toLocaleDateString('ko-KR',{timeZone:'Asia/Seoul'}))}</span>`:''}${task.important?'<span class="tag blue">[중요]</span>':''}${task.urgent?'<span class="tag amber">[긴급]</span>':''}</small></div>${controls}</div>`;
}
function groupedActivities(groups) {
  if(!groups.length)return empty('수집된 작업 근거가 없습니다.','필요한 Jira 이슈와 시간을 직접 입력할 수 있습니다.');
  return groups.map(g=>`<article class="activity-group"><h3>${issueLink(g.jiraKey)} ${esc(g.title===g.jiraKey?'':g.title)}</h3>${g.summary?`<p>${esc(g.summary)}</p>`:''}<small>메시지 ${g.messageCount}개 (사용자 ${g.userMessages} / 응답 ${g.assistantMessages}) · 세션 ${g.sessionCount}개${g.activityCount?' · 활동 기록 '+g.activityCount+'건':''}</small>${g.sessions?.length?`<details><summary>세션 보기</summary>${g.sessions.map(x=>`<p>${esc(sourceName(x.source))} · ${esc(x.name)} <small>메시지 ${x.messageCount}개</small></p>`).join('')}</details>`:''}</article>`).join('');
}
async function load() {
  const date=$('#selected-date').value;
  [state,settings]=await Promise.all([api('/api/state'+(date?'?date='+date:'')),api('/api/settings')]);
  document.title=`${settings.agentName} | work-agent`;
  $('#agent-name').textContent=settings.agentName;
  $('#agent-heading').textContent=settings.agentName;
  $('#brand-link').setAttribute('aria-label',settings.agentName+' 홈');
  $('#chat-text').placeholder=`${settings.agentCallName}, 오늘 할 일을 정리해 줘.`;
  $('#selected-date').value=state.date;
  $('#date-label').textContent=`${state.date.slice(0,4)}년 ${shortDay(state.date)} · 한국 시간`;
  $('#connection-dot').classList.toggle('ready',settings.slackConnected&&settings.jiraConfigured);
  planIds=state.plan.map(t=>t.id);
  const available=new Set([...state.tasks,...state.hiddenTasks].map(t=>t.id));for(const id of selectedTasks)if(!available.has(id))selectedTasks.delete(id);
  if(!worklogDay||monday(worklogDay)!==monday(state.date))worklogDay=state.date;
  render();
}
function render() {
  document.querySelectorAll('[data-view]').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.setAttribute('aria-current',b.dataset.view===view?'page':'false');});
  if(view==='today')renderToday(); if(view==='plan')renderPlan(); if(view==='worklog')renderWorklog(); if(view==='team')renderTeam();
}
function priority(task){return (task.urgent?0:2)+(task.important?0:1);}
function sortTasks(tasks,sort){return [...tasks].sort((a,b)=>{
  if(sort.startsWith('created-')){const first=Date.parse(a.createdAt),second=Date.parse(b.createdAt);if(!Number.isFinite(first))return Number.isFinite(second)?1:0;if(!Number.isFinite(second))return -1;return sort==='created-desc'?second-first:first-second;}
  return sort==='due'?(a.due||'9999').localeCompare(b.due||'9999')||priority(a)-priority(b):sort==='priority'?priority(a)-priority(b)||(a.due||'9999').localeCompare(b.due||'9999'):0;
});}
function todayList(){return state.plan.filter(t=>todayStatus==='all'||(todayStatus==='done'?t.status==='done':t.status!=='done'));}
function todayRow(task){return `<div class="today-task" data-today-id="${esc(task.id)}"><button class="drag-handle" data-drag-task="${esc(task.id)}" aria-label="${esc(task.title)} 순서 이동" title="드래그하여 이동 · 키보드 Alt+위/아래">⠿</button>${taskRow(task,0,`<div class="row-actions"><button class="icon-button today-remove" data-today-remove="${esc(task.id)}" aria-label="${esc(task.title)} 오늘 할 일에서 제외" title="오늘 할 일에서 제외">×</button></div>`)}</div>`;}
function renderToday() {
  const jobs=state.jobs.filter(j=>!j.reviewed),tasks=todayList(),ids=new Set(planIds),candidates=state.todayGroups.flatMap(g=>g.tasks).filter(t=>!ids.has(t.id));
  $('#content').innerHTML=heading('오늘 할 일','미완료 업무를 이어받고, 하루 동안 바뀌는 할 일을 바로 조정하세요.',`<button class="button" data-report="briefing">데일리 브리핑</button><button class="button" data-open="task" data-for-today="true">자세히 등록</button>`)+`
  <div class="today-grid"><section class="sheet today-sheet"><div class="sheet-header"><h2>오늘 할 일 <span class="tag blue">${state.plan.filter(t=>t.status!=='done').length}건</span></h2><span class="save-hint" role="status" id="plan-save-state">자동 저장됨</span></div><div class="sheet-body">
  <form id="quick-task-form" class="quick-task"><label class="sr-only" for="quick-task-title">오늘 할 일 추가</label><input id="quick-task-title" name="title" placeholder="오늘 할 일을 입력하고 Enter" required maxlength="300"><button class="button primary" type="submit">추가</button></form>
  <div class="task-toolbar"><div class="list-filters">${[['open','할 일'],['done','오늘 마친 일'],['all','전체']].map(([key,label])=>`<button class="filter ${todayStatus===key?'active':''}" data-today-status="${key}">${label}</button>`).join('')}</div><label>정렬 <select id="today-sort" aria-label="오늘 할 일 정렬">${[['priority','중요도·긴급도순'],['due','마감일순'],['created-desc','생성일 최신순'],['created-asc','생성일 오래된 순'],['manual','직접 정한 순서']].map(([v,l])=>`<option value="${v}" ${state.planSort===v?'selected':''}>${l}</option>`).join('')}</select></label></div>
  <p class="hint drag-hint">왼쪽 손잡이를 드래그하면 원하는 순서로 저장됩니다. 목록에서 빼도 활동과 워크로그는 남습니다.</p>
  <div id="today-list">${tasks.length?tasks.map(todayRow).join(''):empty(todayStatus==='done'?'아직 마친 업무가 없습니다.':'오늘 할 일을 추가해 보세요.','아래에서 기존 업무를 선택하거나 위 입력란에 새 업무를 적으세요.')}</div>
  <details class="task-group candidates"><summary>다른 업무에서 추가 <span class="tag">${candidates.length}건</span></summary><div class="candidate-search"><label class="sr-only" for="candidate-search">추가할 업무 찾기</label><input id="candidate-search" placeholder="이슈 번호 또는 업무 이름으로 찾기"></div><div id="candidate-list">${candidates.map(t=>`<div data-candidate-text="${esc((t.title+' '+t.jiraKey).toLowerCase())}">${taskRow(t,0,`<button class="button" data-today-add="${esc(t.id)}">오늘 추가</button>`)}</div>`).join('')}</div></details>
  </div></section><div class="side-stack"><section class="sheet"><div class="sheet-header"><h2>후속 작업 제안</h2><button class="text-button" id="suggest-followups">제안받기</button></div><div class="sheet-body"><p class="hint">어제 완료된 업무 ${state.yesterdayCompleted.length}건의 결과와 댓글을 참고합니다. 제안받기를 누를 때 AI를 호출합니다.</p>${state.followups.length?state.followups.map(s=>`<article class="followup-row"><h3>${esc(s.title)}</h3><p>${esc(s.reason)}</p><small>완료한 업무: ${issueLink(s.sourceJiraKey,s.sourceIssueUrl)} ${esc(s.sourceTitle)}</small><button class="button" data-accept-followup="${esc(s.id)}">오늘 할 일에 추가</button></article>`).join(''):empty('아직 후속 작업 제안이 없습니다.','완료한 업무의 근거가 있을 때 필요한 다음 작업을 제안합니다.')}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>검토할 작업 결과</h2><span class="tag ${jobs.length?'amber':''}">${jobs.length}건</span></div><div class="sheet-body">${jobs.length?jobs.map(j=>`<div class="job-row"><h3>${esc(j.title)}</h3><p>${esc(j.result)}</p><small>${esc(j.verification||'검증 결과가 아직 없습니다.')}</small>${j.artifact?`<small>결과물: ${esc(j.artifact)}</small>`:''}<button class="text-button" data-review-job="${esc(j.id)}">검토 완료로 표시</button></div>`).join(''):empty('검토할 작업 결과가 없습니다.','전날 저녁이나 밤사이 맡긴 작업의 결과가 접수되면 표시합니다.')}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>회의 일정</h2><button class="text-button" data-open="meeting">일정 추가</button></div><div class="sheet-body">${state.meetings.length?state.meetings.map(m=>`<div class="schedule-row"><time>${m.allDay?'종일':clock(m.start)}</time><div><strong>${esc(m.title)}</strong><small>${esc(m.product||'미분류')}</small></div></div>`).join(''):empty('등록된 회의가 없습니다.')}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>데일리 미팅 자료</h2><button class="text-button" data-report="daily">자료 보기</button></div><div class="sheet-body"><p class="hint">어제 한 일과 오늘 할 일을 모읍니다. 공유할 내용이 있으면 덧붙이세요.</p><button class="button daily-add" data-open="daily">추가 공유 내용 작성</button></div></section></div></div>`;
}
function filteredTasks(){const source=filter==='hidden'?state.hiddenTasks:state.tasks;return sortTasks(source.filter(t=>(filter==='hidden'||filter==='all'||(filter==='done'?t.status==='done':t.status!=='done'))&&(!projectFilter||(t.jiraKey?t.jiraKey.split('-')[0]:'local')===projectFilter)&&(!taskSearch||(t.title+' '+t.jiraKey).toLowerCase().includes(taskSearch.toLowerCase()))),taskSort);}
function renderPlan() {
  const visible=filteredTasks(),projects=[...new Set([...state.tasks,...state.hiddenTasks].map(t=>t.jiraKey?t.jiraKey.split('-')[0]:'local'))].sort();
  $('#content').innerHTML=heading('전체 할 일','프로젝트별로 업무를 찾고, 선택한 업무를 한 번에 숨기거나 복원하세요.',`<button class="button primary" data-open="task">할 일 등록</button>`)+`
  <div class="task-toolbar list-toolbar"><div class="list-filters">${[['open','남은 할 일'],['done','완료한 일'],['hidden','숨긴 일'],['all','전체']].map(([key,label])=>`<button class="filter ${filter===key?'active':''}" data-filter="${key}">${label}</button>`).join('')}</div><div class="task-filter-fields"><label>프로젝트 <select id="project-filter" aria-label="프로젝트"><option value="">모든 프로젝트</option>${projects.map(p=>`<option value="${esc(p)}" ${projectFilter===p?'selected':''}>${p==='local'?'직접 등록':esc(p)}</option>`).join('')}</select></label><label>정렬 <select id="task-sort" aria-label="전체 할 일 정렬"><option value="priority" ${taskSort==='priority'?'selected':''}>중요도·긴급도순</option><option value="due" ${taskSort==='due'?'selected':''}>마감일순</option><option value="created-desc" ${taskSort==='created-desc'?'selected':''}>생성일 최신순</option><option value="created-asc" ${taskSort==='created-asc'?'selected':''}>생성일 오래된 순</option></select></label></div></div>
  <div class="sheet"><div class="sheet-header bulk-toolbar"><label class="select-all"><input id="select-all-tasks" type="checkbox" ${visible.length&&visible.every(t=>selectedTasks.has(t.id))?'checked':''}> 표시된 ${visible.length}건 선택</label><div class="bulk-actions"><span id="selection-count">${selectedTasks.size}건 선택</span><button class="button" id="bulk-hide" ${selectedTasks.size?'':'disabled'}>${filter==='hidden'?'선택 복원':'선택 숨기기'}</button><button class="text-button" id="clear-selection">선택 해제</button></div></div><div class="sheet-body"><label class="sr-only" for="task-search">업무 검색</label><input class="task-search" id="task-search" placeholder="이슈 번호 또는 업무 이름으로 검색" value="${esc(taskSearch)}"><div id="all-task-list">${visible.length?visible.map(t=>`<div class="selectable-task"><input type="checkbox" data-select-task="${esc(t.id)}" aria-label="${esc(t.title)} 선택" ${selectedTasks.has(t.id)?'checked':''}>${taskRow(t,0,`<div class="row-actions">${!t.hidden&&t.status!=='done'?`<button data-today-toggle="${esc(t.id)}">${planIds.includes(t.id)?'오늘에서 제외':'오늘 추가'}</button>`:''}<button data-hide-task="${esc(t.id)}">${t.hidden?'복원':'숨기기'}</button></div>`)}</div>`).join(''):empty('조건에 맞는 업무가 없습니다.','프로젝트나 검색 조건을 바꿔 보세요.')}</div></div></div>`;
}
async function saveTodayOrder(ids,sort='manual'){
  if(savingPlan)return;
  savingPlan=true;const label=$('#plan-save-state');if(label)label.textContent='저장 중…';
  try{await api('/api/plan',{date:state.date,taskIds:ids,sort,version:state.planVersion});await load();}catch(e){notice(e.message);await load();}finally{savingPlan=false;}
}
function durationText(seconds) {
  const h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),rest=seconds%60;
  return [h?h+'h':'',m?m+'m':'',rest?rest+'s':''].filter(Boolean).join(' ');
}
function durationSeconds(text) {
  const m=text.match(/^\s*(?:(\d+(?:\.\d+)?)\s*h)?\s*(?:(\d+(?:\.\d+)?)\s*m)?\s*(?:(\d+(?:\.\d+)?)\s*s)?\s*$/i);
  if(!m||!m.slice(1).some(Boolean))return NaN;
  const value=Number(m[1]||0)*3600+Number(m[2]||0)*60+Number(m[3]||0);
  return value>0?value:NaN;
}
function renderWorklog() {
  const start=monday(state.date),days=Array.from({length:7},(_,i)=>dayAdd(start,i));
  const draft=state.worklogs.find(w=>w.date===worklogDay);
  const groups=state.activityGroups.filter(g=>g.date===worklogDay),keys=[...new Set(groups.map(g=>g.jiraKey).filter(Boolean))];
  const rows=draft?.items||keys.map(jiraKey=>({jiraKey,seconds:0,comment:'업무 기록',state:'draft'}));
  $('#content').innerHTML=heading('워크로그','3h, 2.5h 또는 2h 30m처럼 시간을 입력하세요. 하루 8시간을 채울 필요는 없습니다.',`<button class="button" data-report="worklog">주간 자료 보기</button>`)+`
  <div class="worklog-week">${days.map(d=>{const saved=state.worklogs.find(w=>w.date===d);return `<button class="day-button ${d===worklogDay?'active':''}" data-log-day="${d}"><span>${weekday(d)}</span><strong>${Number(d.slice(5,7))}.${Number(d.slice(8,10))}</strong><span>${saved?(saved.items.every(i=>i.state==='posted')?'등록 완료':'시간 초안'):'미작성'}</span></button>`;}).join('')}</div>
  <div class="allocation-layout"><section class="sheet"><div class="sheet-header"><h2>${shortDay(worklogDay)} 작업 시간</h2><span class="tag blue">${draft?'저장된 기록':'새 초안'}</span></div><div class="sheet-body"><form id="worklog-form"><div class="allocation-row worklog-input-row muted"><span>Jira 이슈</span><span>시간</span><span></span></div><div id="worklog-rows">${rows.map(row=>worklogRow(row)).join('')}</div><button type="button" class="text-button" id="add-worklog">+ 다른 이슈 추가</button><div class="allocation-total"><span>입력한 시간 합계</span><strong id="duration-total">0h</strong></div><p class="hint">이날의 작업 근거에 없는 정기 회의나 지원 업무도 이슈 번호로 추가할 수 있습니다. 이미 등록한 기록은 보존합니다.</p><div class="form-actions"><button class="button" type="submit">시간 초안 저장</button><button class="button primary" type="button" id="post-worklog" ${!draft||draft.items.every(i=>i.state==='posted')||!settings.jiraConfigured?'disabled':''}>Jira에 등록</button></div></form>${!settings.jiraConfigured?'<p class="hint">Jira 연결 정보를 설정한 뒤 등록할 수 있습니다. 초안은 먼저 저장할 수 있습니다.</p>':''}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>이날의 작업 근거</h2><button class="text-button" data-open="activity">활동 추가</button></div><div class="sheet-body">${groupedActivities(groups)}<p class="hint">메시지 수는 작업량을 참고하기 위한 수치입니다. 실제 근무 시간으로 환산하지 않습니다.</p></div></section></div>`;
  updateTotal();
}
function worklogRow(row={}) {
  const locked=['posted','posting','unknown'].includes(row.state);
  return `<div class="allocation-row worklog-input-row"><input class="log-key" aria-label="Jira 이슈 번호" placeholder="CT-123" value="${esc(row.jiraKey||'')}" required ${locked?'disabled':''}><input class="log-duration" aria-label="작업 시간" placeholder="2h 30m" value="${esc(durationText(row.seconds||0))}" required ${locked?'disabled':''}>${locked?'<span class="tag">'+logStatus(row.state)+'</span>':'<button class="icon-button" type="button" data-remove-worklog aria-label="시간 기록 삭제">×</button>'}<input class="log-comment" type="hidden" value="${esc(row.comment||'업무 기록')}"><input class="log-marker" type="hidden" value="${esc(row.marker||'')}"></div>`;
}
function updateTotal() {
  let total=0,invalid=false;
  document.querySelectorAll('#worklog-rows .worklog-input-row').forEach(row=>{const field=$('.log-duration',row),seconds=durationSeconds(field.value);if(field.value&&!Number.isFinite(seconds))invalid=true;else if(Number.isFinite(seconds))total+=seconds;});
  if($('#duration-total')){$('#duration-total').textContent=invalid?'시간 형식을 확인하세요':durationText(Math.round(total))||'0h';$('#duration-total').classList.toggle('error',invalid);}
}
async function renderTeam() {
  $('#content').innerHTML=heading('팀원과 1on1','관찰한 사실을 남기고, Jira 업무 현황과 함께 면담을 준비하세요.',`<button class="button" id="sync-team">Jira 주간 정리</button><button class="button primary" data-open="member">팀원 등록</button>`)+`<p class="team-notice">개인 관찰 기록은 이 PC의 업무 수첩에 보관합니다. 데일리 자료나 일반 브리핑에는 포함하지 않습니다.</p><div id="team-content" class="loading">팀원 기록을 불러오고 있습니다.</div>`;
  try {team=await api('/api/team');if(view!=='team')return;if(!selectedMember&&team.members.length)selectedMember=team.members[0].id;renderTeamContent();}catch(e){notice(e.message);}
}
function renderTeamContent() {
  const member=team.members.find(m=>m.id===selectedMember);
  if(!member){$('#team-content').className='sheet';$('#team-content').innerHTML=empty('팀원을 먼저 등록하세요.','이름과 Jira 계정을 입력하면 관찰 기록과 업무 현황을 연결할 수 있습니다.');return;}
  const notes=team.observations.filter(o=>o.memberId===member.id).sort((a,b)=>b.date.localeCompare(a.date));
  const snapshot=team.snapshots.find(s=>s.memberId===member.id);
  $('#team-content').className='';$('#team-content').innerHTML=`<div class="team-list">${team.members.map(m=>`<button class="member-button ${m.id===selectedMember?'active':''}" data-member="${esc(m.id)}">${esc(m.name)}<small>${esc(m.jiraUser)}</small></button>`).join('')}</div><div class="today-grid"><section class="sheet"><div class="sheet-header"><h2>${esc(member.name)} 관찰 기록</h2><button class="text-button" data-open="observation">기록 남기기</button></div><div class="sheet-body">${notes.length?notes.map(n=>`<div class="observation"><small>${esc(n.date)} <span class="tag ${n.type==='칭찬'?'teal':'blue'}">${esc(n.type)}</span></small><p>${esc(n.text)}</p>${n.followUp?`<small>후속 조치: ${esc(n.followUp)}</small>`:''}</div>`).join(''):empty('아직 관찰 기록이 없습니다.','칭찬할 행동이나 피드백할 상황을 구체적으로 남겨 보세요.')}</div></section><div class="side-stack"><section class="sheet"><div class="sheet-header"><h2>Jira 업무 현황</h2><button class="text-button" data-report="oneonone">1on1 자료</button><button class="text-button" id="ask-team-statistics">통계 질문하기</button></div><div class="sheet-body">${snapshot?`<p class="hint">${esc(snapshot.from)} ~ ${esc(snapshot.to)} · 등록 ${hours(snapshot.loggedSeconds)}시간</p>${snapshot.issues.map(i=>`<div class="job-row"><h3>${issueLink(i.id,i.issueUrl)} ${esc(i.title)}</h3><small>${esc(i.jiraStatus)} · 등록 ${hours(i.loggedSeconds)}시간</small></div>`).join('')}<p class="hint">Jira에 등록된 시간만 포함합니다. 병목과 지원 필요 여부는 근거를 확인한 뒤 판단하세요.</p>`:empty('아직 Jira에서 확인하지 않았습니다.','연결 설정 후 Jira 주간 정리를 실행하세요.')}</div></section></div></div>`;
}
function modal(title,html) {$('#modal-title').textContent=title;$('#modal-content').innerHTML=html;$('#modal').showModal();}
function field(label,name,value='',type='text',required=false){return `<div class="field"><label for="field-${name}">${esc(label)}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" ${required?'required':''}></div>`;}
function openForm(kind,id) {
  let title='',html='';const task=id?[...state.tasks,...state.hiddenTasks].find(t=>t.id===id):null;
  if(kind==='task'){title=task?'업무 수정':'할 일 등록';html=field('업무 이름','title',task?.title||'','text',true)+`<div class="field-row">${field('제품','product',task?.product||'')}${field('Jira 이슈 번호','jiraKey',task?.jiraKey||'')}</div>`+field('마감일','due',task?.due||'','date')+`<div class="field"><label for="field-status">상태</label><select id="field-status" name="status" ${task?.jiraKey?'disabled':''}>${['todo','in-progress','done'].map(s=>`<option value="${s}" ${task?.status===s?'selected':''}>${statusName(s)}</option>`).join('')}</select></div><div class="checks"><label><input name="important" type="checkbox" ${task?.important?'checked':''}>중요한 일</label><label><input name="urgent" type="checkbox" ${task?.urgent?'checked':''}>급한 일</label></div>`;}
  if(kind==='activity'){title='활동 기록';html=field('수행한 일','title','','text',true)+field('날짜','date',view==='worklog'?worklogDay:state.date,'date',true)+`<div class="field-row">${field('Jira 이슈 번호','jiraKey')}${field('제품','product')}</div>`;}
  if(kind==='meeting'){title='회의 일정 추가';html=field('회의 이름','title','','text',true)+field('시작 시각 (한국 시간)','start',state.date+'T10:00','datetime-local',true)+field('종료 시각 (모르면 비워 두세요)','end','','datetime-local')+field('제품','product')+'<p class="hint">업무 에이전트 일정에 저장합니다. 외부 캘린더에는 등록하지 않습니다.</p>';}
  if(kind==='daily'){title='데일리 추가 공유';html=`<div class="field"><label for="daily-text">덧붙일 내용</label><textarea id="daily-text" name="text" rows="6" placeholder="공유할 문제나 도움이 필요한 일이 있을 때 적어 주세요.">${esc(state.dailyNote)}</textarea></div><p class="hint">어제 한 일과 오늘 할 일은 활동 기록과 계획에서 가져옵니다.</p>`;}
  if(kind==='member'){title='팀원 등록';html=field('이름','name','','text',true)+field('Jira accountId 또는 이메일','jiraUser','','text',true);}
  if(kind==='observation'){title='관찰 기록';html=field('날짜','date',state.date,'date',true)+`<div class="field"><label for="field-type">종류</label><select id="field-type" name="type"><option>관찰</option><option>칭찬</option><option>피드백</option><option>후속 조치</option></select></div><div class="field"><label for="observation-text">관찰한 상황과 행동</label><textarea id="observation-text" name="text" rows="4" required></textarea></div>`+field('후속 조치','followUp');}
  modal(title,`<form id="record-form" data-kind="${kind}" data-id="${esc(id||'')}">${html}<div class="form-actions"><button class="button primary" type="submit">저장</button></div></form>`);
}
async function showReport(kind) {
  const params=new URLSearchParams({kind,date:state.date});if(kind==='oneonone')params.set('memberId',selectedMember);
  const result=await api('/api/report?'+params);
  modal({daily:'데일리 미팅 자료',briefing:'데일리 브리핑',worklog:'주간 워크로그',oneonone:'1on1 준비 자료'}[kind],`<pre id="report-text">${esc(result.text)}</pre><div class="form-actions"><button class="button" id="copy-report">내용 복사</button><a class="button primary" href="/report.html?${esc(params.toString())}" target="_blank" rel="noopener">HTML 보고서 열기</a></div>`);
}
document.addEventListener('click',async event=>{
  const b=event.target.closest('button');if(!b)return;
  try {
    if(b.dataset.view||b.dataset.go){view=b.dataset.view||b.dataset.go;render();}
    if(b.dataset.open){openForm(b.dataset.open);if(b.dataset.forToday)$('#record-form').dataset.forToday='true';}
    if(b.dataset.editTask)openForm('task',b.dataset.editTask);
    if(b.dataset.filter){filter=b.dataset.filter;selectedTasks.clear();renderPlan();}
    if(b.dataset.todayStatus){todayStatus=b.dataset.todayStatus;renderToday();}
    if(b.dataset.todayAdd||b.dataset.todayRemove||b.dataset.todayToggle){const id=b.dataset.todayAdd||b.dataset.todayRemove||b.dataset.todayToggle;const included=b.dataset.todayAdd?true:b.dataset.todayRemove?false:!planIds.includes(id);b.disabled=true;await api('/api/plan/item',{date:state.date,id,included});await load();notice(included?'오늘 할 일에 추가했습니다.':'오늘 할 일에서 제외했습니다. 활동과 워크로그는 보존했습니다.');}
    if(b.id==='clear-selection'){selectedTasks.clear();renderPlan();}
    if(b.id==='bulk-hide'){b.disabled=true;const count=selectedTasks.size;await api('/api/tasks/hide-many',{taskIds:[...selectedTasks],hidden:filter!=='hidden'});selectedTasks.clear();await load();notice(`${count}건을 ${filter==='hidden'?'복원했습니다.':'내 목록에서 숨겼습니다. Jira 상태는 바꾸지 않았습니다.'}`);}
    if(b.id==='suggest-followups'){b.disabled=true;b.textContent='제안 준비 중…';try{const result=await api('/api/followups?date='+state.date,{});await load();notice(result.message);}finally{if(b.isConnected){b.disabled=false;b.textContent='제안받기';}}}
    if(b.dataset.acceptFollowup){b.disabled=true;await api('/api/followups/accept',{id:b.dataset.acceptFollowup,date:state.date});await load();notice('후속 작업을 오늘 할 일에 추가했습니다.');}
    if(b.dataset.hideTask){const task=[...state.tasks,...state.hiddenTasks].find(t=>t.id===b.dataset.hideTask);await api('/api/tasks/hide',{id:task.id,hidden:!task.hidden});await load();notice(task.hidden?'목록에 다시 표시했습니다.':'내 목록에서 숨겼습니다. Jira 상태는 바꾸지 않았습니다.');}
    if(b.id==='ask-team-statistics'){const member=team.members.find(m=>m.id===selectedMember);$('#chat-body').hidden=false;$('#toggle-chat').textContent='대화 접기';$('#toggle-chat').setAttribute('aria-expanded','true');$('#chat-text').value=`${settings.agentCallName}, ${member.name}이 이번 하반기에 담당한 이슈 수와 등록 시간을 알려줘.`;$('#chat-text').focus();}
    if(b.dataset.report)await showReport(b.dataset.report);
    if(b.dataset.evidence){const e=await api('/api/evidence?id='+encodeURIComponent(b.dataset.evidence));modal('작업 근거',`<p class="hint">${esc(e.sourceRef||'')}</p><pre>${esc(e.text)}</pre>`);}
    if(b.dataset.logDay){worklogDay=b.dataset.logDay;renderWorklog();}
    if(b.id==='add-worklog'){$('#worklog-rows').insertAdjacentHTML('beforeend',worklogRow());$('#post-worklog').disabled=true;}
    if(b.hasAttribute('data-remove-worklog')){b.closest('.worklog-input-row').remove();updateTotal();$('#post-worklog').disabled=true;}
    if(b.id==='post-worklog'){b.disabled=true;await api('/api/worklogs/post',{date:worklogDay});await load();notice('워크로그를 Jira에 등록했습니다.');}
    if(b.dataset.member){selectedMember=b.dataset.member;renderTeamContent();}
    if(b.id==='sync-team'){b.disabled=true;await api('/api/sync',{kind:'team'});await renderTeam();notice('팀원별 Jira 업무와 등록 시간을 확인했습니다.');}
    if(b.dataset.reviewJob){const job=state.jobs.find(j=>j.id===b.dataset.reviewJob);await api('/api/jobs',{...job,reviewed:true});await load();notice('검토 완료로 표시했습니다.');}
    if(b.id==='copy-report'){await navigator.clipboard.writeText($('#report-text').textContent);notice('보고서 내용을 복사했습니다.');}
  }catch(e){notice(e.message);if(b.id==='post-worklog'){await load();}else b.disabled=false;}
});
document.addEventListener('focusin',async event=>{
  const el=event.target;if(!el.matches('[data-task-status][data-remote="true"]')||el.dataset.loaded||el.dataset.loading)return;
  el.dataset.loading='true';
  try {const data=await api('/api/tasks/transitions?id='+encodeURIComponent(el.dataset.taskStatus));el.innerHTML=`<option value="">${esc(data.current.jiraStatus)}</option>`+data.transitions.map(t=>{const required=Object.values(t.fields||{}).some(f=>f.required&&!f.hasDefaultValue&&f.defaultValue==null);return `<option value="${esc(t.id)}" ${required?'disabled':''}>${esc(t.to.name)}${t.name!==t.to.name?' ('+esc(t.name)+')':''}${required?' · Jira에서 추가 입력 필요':''}</option>`;}).join('');el.dataset.loaded='true';}
  catch(e){notice(e.message);}finally{delete el.dataset.loading;}
});
document.addEventListener('change',async event=>{
  const el=event.target;
  try {
    if(el.dataset.taskStatus&&el.value){const body={id:el.dataset.taskStatus};if(el.dataset.remote==='true')body.transitionId=el.value;else body.status=el.value;el.disabled=true;await api('/api/tasks/status',body);await load();notice('업무 상태를 변경했습니다.');}
    if(el.id==='selected-date'){worklogDay='';await load();}
    if(el.id==='today-sort')await saveTodayOrder(planIds,el.value);
    if(el.id==='project-filter'){projectFilter=el.value;selectedTasks.clear();renderPlan();}
    if(el.id==='task-sort'){taskSort=el.value;renderPlan();}
    if(el.dataset.selectTask){el.checked?selectedTasks.add(el.dataset.selectTask):selectedTasks.delete(el.dataset.selectTask);$('#selection-count').textContent=selectedTasks.size+'건 선택';$('#bulk-hide').disabled=!selectedTasks.size;$('#select-all-tasks').checked=filteredTasks().every(t=>selectedTasks.has(t.id));}
    if(el.id==='select-all-tasks'){for(const task of filteredTasks())el.checked?selectedTasks.add(task.id):selectedTasks.delete(task.id);renderPlan();}
  }catch(e){notice(e.message);await load();}
});
document.addEventListener('input',event=>{const el=event.target;if(el.closest('#worklog-form')){$('#post-worklog').disabled=true;updateTotal();}if(el.id==='candidate-search'){const text=el.value.toLowerCase();document.querySelectorAll('[data-candidate-text]').forEach(row=>row.hidden=!row.dataset.candidateText.includes(text));}if(el.id==='task-search'){taskSearch=el.value;const position=el.selectionStart;renderPlan();const field=$('#task-search');field.focus();field.setSelectionRange(position,position);}});
document.addEventListener('pointerdown',event=>{const handle=event.target.closest('[data-drag-task]');if(!handle||savingPlan||event.button!==0)return;event.preventDefault();draggedId=handle.dataset.dragTask;dragOriginY=event.clientY;didDrag=false;handle.setPointerCapture(event.pointerId);});
document.addEventListener('pointermove',event=>{if(!draggedId||Math.abs(event.clientY-dragOriginY)<5&&!didDrag)return;didDrag=true;const dragged=[...document.querySelectorAll('.today-task')].find(t=>t.dataset.todayId===draggedId);if(!dragged)return;dragged.classList.add('dragging');const target=document.elementFromPoint(event.clientX,event.clientY)?.closest('.today-task');if(target&&target!==dragged&&target.closest('#today-list')){const rect=target.getBoundingClientRect();target.parentNode.insertBefore(dragged,event.clientY<rect.top+rect.height/2?target:target.nextSibling);}if(event.clientY>innerHeight-40)window.scrollBy(0,18);else if(event.clientY<40)window.scrollBy(0,-18);});
document.addEventListener('pointerup',async()=>{if(!draggedId)return;const ids=[...document.querySelectorAll('#today-list [data-today-id]')].map(t=>t.dataset.todayId);draggedId='';document.querySelectorAll('.dragging').forEach(t=>t.classList.remove('dragging'));if(didDrag)await saveTodayOrder([...ids,...planIds.filter(id=>!ids.includes(id))]);didDrag=false;});
document.addEventListener('pointercancel',()=>{if(draggedId){draggedId='';didDrag=false;render();}});
document.addEventListener('keydown',async event=>{const handle=event.target.closest('[data-drag-task]');if(!handle||!event.altKey||!['ArrowUp','ArrowDown'].includes(event.key))return;event.preventDefault();const ids=todayList().map(t=>t.id),from=ids.indexOf(handle.dataset.dragTask),to=from+(event.key==='ArrowUp'?-1:1);if(to<0||to>=ids.length)return;[ids[from],ids[to]]=[ids[to],ids[from]];await saveTodayOrder([...ids,...planIds.filter(id=>!ids.includes(id))]);});
document.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target;const submit=$('[type="submit"]',form);if(submit)submit.disabled=true;
  try {
    if(form.id==='quick-task-form'){await api('/api/tasks',{title:$('#quick-task-title').value.trim(),today:true,date:state.date});await load();notice('오늘 할 일에 추가했습니다.');}
    if(form.id==='record-form'){
      const kind=form.dataset.kind,data=Object.fromEntries(new FormData(form));
      if(form.dataset.id)data.id=form.dataset.id;
      if(kind==='task'){data.important=form.elements.important.checked;data.urgent=form.elements.urgent.checked;if(!data.product)data.product='미분류';if(form.dataset.forToday){data.today=true;data.date=state.date;}}
      if(kind==='meeting'){data.start+=':00+09:00';if(data.end)data.end+=':00+09:00';}
      if(kind==='daily')data.date=state.date;
      if(kind==='observation')data.memberId=selectedMember;
      await api('/api/'+({task:'tasks',activity:'activities',meeting:'meetings',daily:'daily',member:'members',observation:'observations'}[kind]),data);
      $('#modal').close();await load();notice('저장했습니다.');
    }
    if(form.id==='worklog-form'){
      const items=[...document.querySelectorAll('#worklog-rows .worklog-input-row')].map(row=>({jiraKey:$('.log-key',row).value.trim().toUpperCase(),duration:$('.log-duration',row).value,comment:$('.log-comment',row).value,marker:$('.log-marker',row).value}));
      await api('/api/worklogs',{date:worklogDay,items});await load();notice('작업 시간을 저장했습니다. Jira 등록은 별도로 실행하세요.');
    }
    if(form.id==='chat-form'){
      const text=$('#chat-text').value.trim();if(!text)return;appendMessage('user',text);$('#chat-text').value='';
      const result=await api('/api/chat',{text,requestId:crypto.randomUUID()});appendMessage('assistant',result.text);await load();
    }
  }catch(e){notice(e.message);if(form.id==='chat-form')appendMessage('assistant',e.message);}
  finally{if(submit)submit.disabled=false;}
});
function appendMessage(role,text){const node=document.createElement('div');node.className='message '+role;const label=document.createElement('strong');label.textContent=role==='user'?'나':settings?.agentName||'work-agent';node.append(label,document.createTextNode(text));$('#messages').append(node);$('#messages').scrollTop=$('#messages').scrollHeight;}
$('#toggle-chat').addEventListener('click',async()=>{const body=$('#chat-body');body.hidden=!body.hidden;$('#toggle-chat').textContent=body.hidden?'대화 열기':'대화 접기';$('#toggle-chat').setAttribute('aria-expanded',!body.hidden);if(!body.hidden&&!$('#messages').children.length){try{const messages=await api('/api/messages');messages.slice(-20).forEach(m=>appendMessage(m.role,m.text));}catch(e){notice(e.message);}}});
$('#close-modal').addEventListener('click',()=>$('#modal').close());
$('#refresh').addEventListener('click',async()=>{const b=$('#refresh');b.disabled=true;b.textContent='확인 중…';try{const result=await api('/api/sync',{kind:'all'});await load();const failed=state.sync.filter(s=>s.ok===false);notice(failed.length?'일부 연결을 확인하지 못했습니다. 연결 상태를 확인하세요.':'새 기록을 확인했습니다.');}catch(e){notice(e.message);}finally{b.disabled=false;b.textContent='기록 새로 확인';}});
$('#connections').addEventListener('click',()=>modal('연결 상태',`<div class="connection-row"><div><strong>Slack</strong><small>SLACK_APP_TOKEN · SLACK_BOT_TOKEN · SLACK_USER_ID</small></div><span class="${settings.slackConnected?'':'muted'}">${settings.slackConnected?'연결됨':settings.slackConfigured?'연결 확인 중':'설정 필요'}</span></div><div class="connection-row"><div><strong>Jira</strong><small>JIRA_URL · JIRA_EMAIL · JIRA_TOKEN_ENV에 지정한 환경변수</small></div><span>${settings.jiraConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>캘린더</strong><small>CALENDAR_ICS_URL 또는 CALENDAR_ICS_FILE</small></div><span>${settings.calendarConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>자연어 처리</strong><small>로그인한 ${esc(settings.engine)} CLI를 사용합니다.</small></div><span>${esc(settings.engine)}</span></div><div class="connection-row"><div><strong>정기 브리핑</strong><small>평일 ${esc(settings.briefingTime||'꺼짐')} · 금요일 워크로그 ${esc(settings.fridayTime||'꺼짐')}<br>Asia/Seoul · 프로그램이 실행 중일 때 동작합니다.</small></div></div><div class="connection-row"><div><strong>Claude 예약 요청</strong><small>매일 한국 시간 ${esc(settings.claudeSessionTime||'꺼짐')} · 주말 포함<br>최근 결과: ${esc(({complete:'완료',failed:'실패',running:'결과 확인 필요'})[settings.claudeSession?.status]||'실행 전')}</small></div></div><p class="hint">프로젝트의 config.properties에 값을 입력한 뒤 프로그램을 다시 시작하세요. 자세한 절차는 README.md에 있습니다.</p>${settings.slackError?`<p class="hint error">${esc(settings.slackError)}</p>`:''}`));
load().catch(e=>{$('#content').innerHTML=empty('업무 기록을 불러오지 못했습니다.',e.message);});
setInterval(async()=>{
  if(!state||document.hidden||savingPlan||draggedId||$('#modal').open||!['today','plan'].includes(view)||document.activeElement.matches('input,textarea,select')||$('.candidates[open]'))return;
  try{const next=await api('/api/state?date='+state.date);if(JSON.stringify([next.tasks,next.hiddenTasks,next.planVersion,next.jobs,next.followups])!==JSON.stringify([state.tasks,state.hiddenTasks,state.planVersion,state.jobs,state.followups])){state=next;planIds=state.plan.map(t=>t.id);render();}}catch{}
},30000);
