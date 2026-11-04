'use strict';
const $ = (s, root = document) => root.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
let state, settings, team, view = 'today', filter = 'open', planIds = [], selectedMember = '', worklogDay = '', allocationDirty = false;
let noticeTimer;
const sourceName = source => ({claude:'Claude',codex:'Codex',manual:'직접',calendar:'캘린더'}[source] || source);
const statusName = status => ({open:'할 일',doing:'진행 중',blocked:'도움 필요',done:'완료'}[status] || status);
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
function taskRow(task,index,controls='') {
  return `<div class="task-row ${task.status==='done'?'done':''}"><input class="task-check" type="checkbox" ${task.status==='done'?'checked':''} data-task-check="${esc(task.id)}" aria-label="${esc(task.title)} 완료"><div class="task-detail"><strong>${esc(task.title)}</strong><small><span>${esc(task.product||'미분류')}</span>${task.jiraKey?`<span>${esc(task.jiraKey)}</span>`:''}${task.due?`<span class="${task.due<state.date?'error':''}">마감 ${esc(task.due)}</span>`:''}${task.status==='blocked'?'<span class="tag amber">도움 필요</span>':''}${task.important?'<span class="tag blue">중요</span>':''}${task.urgent?'<span class="tag amber">긴급</span>':''}</small></div>${controls}</div>`;
}
function activityRows(activities) {
  return activities.length?activities.map(a=>`<div class="activity-row"><span class="activity-source">${esc(sourceName(a.source))}</span><div class="activity-body"><p>${esc(a.title)}</p><small>${esc(a.jiraKey||'Jira 이슈 미지정')}${a.source!=='manual'?' · CLI 응답에서 모은 기록입니다.':''} ${a.evidenceId?`<button class="text-button" data-evidence="${esc(a.id)}">근거 보기</button>`:''}</small></div><span class="activity-time">${a.occurredAt?clock(a.occurredAt):''}</span></div>`).join(''):empty('아직 등록된 활동이 없습니다.','CLI 기록을 확인하거나 회의·검토·지원 업무를 직접 기록하세요.');
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
  if(!worklogDay||monday(worklogDay)!==monday(state.date))worklogDay=state.date;
  render();
}
function render() {
  document.querySelectorAll('[data-view]').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.setAttribute('aria-current',b.dataset.view===view?'page':'false');});
  if(view==='today')renderToday(); if(view==='plan')renderPlan(); if(view==='worklog')renderWorklog(); if(view==='team')renderTeam();
}
function renderToday() {
  const today=state.activities.filter(a=>a.date===state.date), jobs=state.jobs.filter(j=>!j.reviewed);
  const chosen=state.planSaved?state.plan:state.tasks.filter(t=>t.status!=='done').slice(0,5);
  const meetings=state.meetings;
  $('#content').innerHTML=heading('오늘의 업무','기록을 확인하고, 오늘 집중할 일을 정하세요.',`<button class="button" data-report="briefing">아침 브리핑</button><button class="button primary" data-open="task">할 일 등록</button>`)+`
  <div class="today-grid"><div><section class="sheet"><div class="sheet-header"><h2>오늘의 계획</h2><button class="text-button" data-go="plan">계획 조정</button></div><div class="sheet-body"><div class="plan-intro"><span class="tag ${state.planSaved?'teal':'blue'}">${state.planSaved?'저장한 계획':'우선순위에 따른 제안'}</span><span>${chosen.length}개의 업무</span></div>${chosen.length?chosen.map((t,i)=>taskRow(t,i)).join(''):empty('오늘 할 일을 정해 보세요.','새 업무를 등록하거나 기존 할 일에서 선택할 수 있습니다.','<br><button class="button" data-go="plan">계획 세우기</button>')}</div></section>
  <section class="activity-section"><div class="section-title"><h2>오늘 남긴 기록</h2><button class="text-button" data-open="activity">활동 기록</button></div>${activityRows(today)}</section></div>
  <div class="side-stack"><section class="sheet"><div class="sheet-header"><h2>회의 일정</h2><button class="text-button" data-open="meeting">일정 추가</button></div><div class="sheet-body">${meetings.length?meetings.map(m=>`<div class="schedule-row"><time>${m.allDay?'종일':clock(m.start)}</time><div><strong>${esc(m.title)}</strong><small>${esc(m.product||'미분류')} · ${m.source==='calendar'?'캘린더':'업무 에이전트 일정'}</small></div></div>`).join(''):empty('등록된 회의가 없습니다.',settings.calendarConfigured?'캘린더를 새로 확인할 수 있습니다.':'연결 상태에서 캘린더 설정 방법을 확인하세요.')}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>검토할 작업 결과</h2><span class="tag ${jobs.length?'amber':''}">${jobs.length}건</span></div><div class="sheet-body">${jobs.length?jobs.map(j=>`<div class="job-row"><h3>${esc(j.title)}</h3><p>${esc(j.result)}</p><small>${esc(j.verification||'검증 결과가 아직 없습니다.')}</small>${j.artifact?`<small>결과물: ${esc(j.artifact)}</small>`:''}<button class="text-button" data-review-job="${esc(j.id)}">검토 완료로 표시</button></div>`).join(''):empty('새로 접수된 결과가 없습니다.','밤사이 실행한 작업 결과를 연결하면 이곳에서 확인할 수 있습니다.')}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>데일리 미팅 자료</h2><button class="text-button" data-report="daily">자료 보기</button></div><div class="sheet-body"><p class="hint">어제 한 일과 오늘 할 일을 모읍니다. 공유할 문제가 있으면 추가로 덧붙이세요.</p><button class="button daily-add" data-open="daily">추가 공유 내용 작성</button></div></section></div></div>`;
}
function renderPlan() {
  const visible=state.tasks.filter(t=>filter==='all'||(filter==='done'?t.status==='done':t.status!=='done'));
  $('#content').innerHTML=heading('할 일과 계획','중요도와 긴급도를 조정하고, 오늘 할 일을 순서대로 고르세요.',`<button class="button primary" data-open="task">할 일 등록</button>`)+`
  <div class="plan-layout"><section><div class="list-filters">${[['open','남은 할 일'],['done','완료한 일'],['all','전체']].map(([key,label])=>`<button class="filter ${filter===key?'active':''}" data-filter="${key}">${label}</button>`).join('')}</div><div class="sheet"><div class="sheet-body">${visible.length?visible.map((t,i)=>taskRow(t,i,`<div class="row-actions"><button data-edit-task="${esc(t.id)}" aria-label="${esc(t.title)} 수정">수정</button><button data-plan-add="${esc(t.id)}" ${planIds.includes(t.id)?'disabled':''} aria-label="${esc(t.title)} 계획에 추가">+</button></div>`)).join(''):empty('표시할 업무가 없습니다.','할 일을 등록하면 중요도와 마감일을 기준으로 정리합니다.')}</div></div></section>
  <section class="sheet"><div class="sheet-header"><h2>${shortDay(state.date)} 계획</h2><span class="tag blue">${planIds.length}건</span></div><div class="sheet-body" id="plan-list">${planRows()}<div class="form-actions"><button class="button primary" id="save-plan">이 순서로 계획 저장</button></div></div></section></div>`;
}
function planRows() {return planIds.length?planIds.map((id,i)=>{const task=state.tasks.find(t=>t.id===id);return task?`<div class="task-row"><span class="task-order">${i+1}</span><div class="task-detail"><strong>${esc(task.title)}</strong><small>${esc(task.jiraKey||task.product)}</small></div><div class="row-actions"><button data-plan-up="${i}" ${i===0?'disabled':''} aria-label="${esc(task.title)} 위로">↑</button><button data-plan-down="${i}" ${i===planIds.length-1?'disabled':''} aria-label="${esc(task.title)} 아래로">↓</button><button data-plan-remove="${i}" aria-label="${esc(task.title)} 계획에서 제외">×</button></div></div>`:'';}).join(''):empty('오늘 할 업무를 선택하세요.','왼쪽 목록의 + 버튼으로 추가합니다.');}
function renderWorklog() {
  const start=monday(state.date), days=Array.from({length:7},(_,i)=>dayAdd(start,i));
  const draft=state.worklogs.find(w=>w.date===worklogDay), locked=draft?.items.some(i=>['posted','posting','unknown'].includes(i.state));
  const activities=state.activities.filter(a=>a.date===worklogDay), keys=[...new Set(activities.map(a=>a.jiraKey).filter(Boolean))];
  const shares=draft?.items||keys.map(jiraKey=>({jiraKey,percent:'',seconds:0,comment:'업무 기록',state:'draft'}));
  allocationDirty=false;
  $('#content').innerHTML=heading('워크로그','작업 내용을 확인한 뒤 직접 비율을 정하세요. 기록 길이로 시간을 추정하지 않습니다.',`<button class="button" data-report="worklog">주간 자료 보기</button>`)+`
  <div class="worklog-week">${days.map(d=>{const saved=state.worklogs.find(w=>w.date===d);return `<button class="day-button ${d===worklogDay?'active':''}" data-log-day="${d}"><span>${weekday(d)}</span><strong>${Number(d.slice(5,7))}.${Number(d.slice(8,10))}</strong><span>${saved?(saved.items.every(i=>i.state==='posted')?'등록 완료':'배분 초안'):'미작성'}</span></button>`;}).join('')}</div>
  <div class="allocation-layout"><section class="sheet"><div class="sheet-header"><h2>${shortDay(worklogDay)} 시간 배분</h2><span class="tag blue">${draft?'저장된 기록':'새 초안'}</span></div><div class="sheet-body"><form id="allocation-form"><div class="field"><label for="total-minutes">하루 기준 시간 (분)</label><input id="total-minutes" type="number" min="1" max="1440" value="${draft?.totalMinutes||480}" ${locked?'disabled':''}><small>기본 480분은 8시간입니다. 반차인 날에는 240분으로 바꿀 수 있습니다.</small></div><div class="allocation-row muted"><span>Jira 이슈</span><span>비율</span><span>시간</span><span></span></div><div id="allocation-rows">${shares.map(s=>allocationRow(s,locked)).join('')}</div>${!locked?'<button type="button" class="text-button" id="add-share">+ 이슈 추가</button>':''}<div class="allocation-total"><span>배분 합계</span><strong id="percent-total">0%</strong></div>${locked?'<p class="hint">Jira에 등록했거나 등록 결과를 확인 중인 날짜입니다. 기존 기록을 덮어쓰지 않습니다.</p>':''}<div class="form-actions">${!locked?'<button class="button" type="submit">배분 초안 저장</button>':''}<button class="button primary" type="button" id="post-worklog" ${!draft||draft.items.every(i=>i.state==='posted')||!settings.jiraConfigured?'disabled':''}>Jira에 등록</button></div></form>${!settings.jiraConfigured?'<p class="hint">Jira 연결 정보를 설정한 뒤 등록할 수 있습니다. 초안은 먼저 저장할 수 있습니다.</p>':''}</div></section>
  <section class="sheet"><div class="sheet-header"><h2>이날의 작업 근거</h2><button class="text-button" data-open="activity">활동 추가</button></div><div class="sheet-body">${activityRows(activities)}<p class="hint">같은 이슈를 여러 도구에서 작업해도 비율은 이슈별로 한 번만 정합니다. 회의·지원 업무에 사용할 이슈도 추가할 수 있습니다.</p></div></section></div>`;
  updateTotal();
}
function allocationRow(s={},locked=false) {return `<div class="allocation-row"><input class="share-key" aria-label="Jira 이슈 번호" placeholder="CT-123" value="${esc(s.jiraKey||'')}" required ${locked?'disabled':''}><div class="percent-field"><input class="share-percent" aria-label="비율" type="number" min="0.001" max="100" step="any" value="${esc(s.percent??'')}" required ${locked?'disabled':''}><span>%</span></div><span class="hours">${hours(s.seconds||0)}h</span>${locked?'<span></span>':'<button class="icon-button" type="button" data-remove-share aria-label="이슈 배분 삭제">×</button>'}<input class="share-comment" type="hidden" value="${esc(s.comment||'업무 기록')}"></div>`;}
function updateTotal() {
  const total=Number($('#total-minutes')?.value||480)*60;let percent=0;
  document.querySelectorAll('#allocation-rows .allocation-row').forEach(row=>{const p=Number($('.share-percent',row).value)||0;percent+=p;$('.hours',row).textContent=hours(total*p/100)+'h';});
  if($('#percent-total')){$('#percent-total').textContent=Number(percent.toFixed(4))+'%';$('#percent-total').classList.toggle('error',Math.abs(percent-100)>.00001);}
}
async function renderTeam() {
  $('#content').innerHTML=heading('팀원과 1on1','관찰한 사실을 남기고, Jira 업무 현황과 함께 면담을 준비하세요.',`<button class="button" id="sync-team">Jira 주간 정리</button><button class="button primary" data-open="member">팀원 등록</button>`)+`<p class="team-notice">개인 관찰 기록은 이 PC의 업무 수첩에 보관합니다. 데일리 자료나 일반 브리핑에는 포함하지 않습니다.</p><div id="team-content" class="loading">팀원 기록을 불러오고 있습니다.</div>`;
  try {team=await api('/api/team');if(view!=='team')return;if(!selectedMember&&team.members.length)selectedMember=team.members[0].id;renderTeamContent();}catch(e){notice(e.message);}
}
function renderTeamContent() {
  const member=team.members.find(m=>m.id===selectedMember);
  if(!member){$('#team-content').className='sheet';$('#team-content').innerHTML=empty('팀원을 먼저 등록하세요.','이름, Jira 사용자명, 역할을 입력하면 관찰 기록과 주간 업무 현황을 연결할 수 있습니다.');return;}
  const notes=team.observations.filter(o=>o.memberId===member.id).sort((a,b)=>b.date.localeCompare(a.date));
  const snapshot=team.snapshots.find(s=>s.memberId===member.id);
  $('#team-content').className='';$('#team-content').innerHTML=`<div class="team-list">${team.members.map(m=>`<button class="member-button ${m.id===selectedMember?'active':''}" data-member="${esc(m.id)}">${esc(m.name)}<small>${esc(m.role||'역할 미입력')}</small></button>`).join('')}</div><div class="today-grid"><section class="sheet"><div class="sheet-header"><h2>${esc(member.name)} 관찰 기록</h2><button class="text-button" data-open="observation">기록 남기기</button></div><div class="sheet-body">${notes.length?notes.map(n=>`<div class="observation"><small>${esc(n.date)} <span class="tag ${n.type==='칭찬'?'teal':'blue'}">${esc(n.type)}</span></small><p>${esc(n.text)}</p>${n.followUp?`<small>후속 조치: ${esc(n.followUp)}</small>`:''}</div>`).join(''):empty('아직 관찰 기록이 없습니다.','칭찬할 행동이나 피드백할 상황을 구체적으로 남겨 보세요.')}</div></section><div class="side-stack"><section class="sheet"><div class="sheet-header"><h2>Jira 업무 현황</h2><button class="text-button" data-report="oneonone">1on1 자료</button></div><div class="sheet-body">${snapshot?`<p class="hint">${esc(snapshot.from)} ~ ${esc(snapshot.to)} · 등록 ${hours(snapshot.loggedSeconds)}시간</p>${snapshot.issues.map(i=>`<div class="job-row"><h3>${esc(i.id)} ${esc(i.title)}</h3><small>${esc(i.jiraStatus)} · 등록 ${hours(i.loggedSeconds)}시간</small></div>`).join('')}<p class="hint">Jira에 등록된 시간만 포함합니다. 병목과 지원 필요 여부는 근거를 확인한 뒤 판단하세요.</p>`:empty('아직 Jira에서 확인하지 않았습니다.','연결 설정 후 Jira 주간 정리를 실행하세요.')}</div></section></div></div>`;
}
function modal(title,html) {$('#modal-title').textContent=title;$('#modal-content').innerHTML=html;$('#modal').showModal();}
function field(label,name,value='',type='text',required=false){return `<div class="field"><label for="field-${name}">${esc(label)}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" ${required?'required':''}></div>`;}
function openForm(kind,id) {
  let title='',html='';const task=id?state.tasks.find(t=>t.id===id):null;
  if(kind==='task'){title=task?'업무 수정':'할 일 등록';html=field('업무 이름','title',task?.title||'','text',true)+`<div class="field-row">${field('제품','product',task?.product||'')}${field('Jira 이슈 번호','jiraKey',task?.jiraKey||'')}</div>`+field('마감일','due',task?.due||'','date')+`<div class="field"><label for="field-status">상태</label><select id="field-status" name="status">${['open','doing','blocked','done'].map(s=>`<option value="${s}" ${task?.status===s?'selected':''}>${statusName(s)}</option>`).join('')}</select></div><div class="checks"><label><input name="important" type="checkbox" ${task?.important?'checked':''}>중요한 일</label><label><input name="urgent" type="checkbox" ${task?.urgent?'checked':''}>급한 일</label></div>`;}
  if(kind==='activity'){title='활동 기록';html=field('수행한 일','title','','text',true)+field('날짜','date',view==='worklog'?worklogDay:state.date,'date',true)+`<div class="field-row">${field('Jira 이슈 번호','jiraKey')}${field('제품','product')}</div>`;}
  if(kind==='meeting'){title='회의 일정 추가';html=field('회의 이름','title','','text',true)+field('시작 시각 (한국 시간)','start',state.date+'T10:00','datetime-local',true)+field('종료 시각 (모르면 비워 두세요)','end','','datetime-local')+field('제품','product')+'<p class="hint">업무 에이전트 일정에 저장합니다. 외부 캘린더에는 등록하지 않습니다.</p>';}
  if(kind==='daily'){title='데일리 추가 공유';html=`<div class="field"><label for="daily-text">덧붙일 내용</label><textarea id="daily-text" name="text" rows="6" placeholder="공유할 문제나 도움이 필요한 일이 있을 때 적어 주세요.">${esc(state.dailyNote)}</textarea></div><p class="hint">어제 한 일과 오늘 할 일은 활동 기록과 계획에서 가져옵니다.</p>`;}
  if(kind==='member'){title='팀원 등록';html=field('이름','name','','text',true)+field('Jira 사용자명 또는 accountId','jiraUser','','text',true)+field('담당 역할','role');}
  if(kind==='observation'){title='관찰 기록';html=field('날짜','date',state.date,'date',true)+`<div class="field"><label for="field-type">종류</label><select id="field-type" name="type"><option>관찰</option><option>칭찬</option><option>피드백</option><option>후속 조치</option></select></div><div class="field"><label for="observation-text">관찰한 상황과 행동</label><textarea id="observation-text" name="text" rows="4" required></textarea></div>`+field('후속 조치','followUp');}
  modal(title,`<form id="record-form" data-kind="${kind}" data-id="${esc(id||'')}">${html}<div class="form-actions"><button class="button primary" type="submit">저장</button></div></form>`);
}
async function showReport(kind) {
  const params=new URLSearchParams({kind,date:state.date});if(kind==='oneonone')params.set('memberId',selectedMember);
  const result=await api('/api/report?'+params);
  modal({daily:'데일리 미팅 자료',briefing:'아침 브리핑',worklog:'주간 워크로그',oneonone:'1on1 준비 자료'}[kind],`<pre id="report-text">${esc(result.text)}</pre><div class="form-actions"><button class="button" id="copy-report">내용 복사</button><a class="button primary" href="/report.html?${esc(params.toString())}" target="_blank" rel="noopener">HTML 보고서 열기</a></div>`);
}
document.addEventListener('click',async event=>{
  const b=event.target.closest('button');if(!b)return;
  try {
    if(b.dataset.view||b.dataset.go){view=b.dataset.view||b.dataset.go;render();}
    if(b.dataset.open)openForm(b.dataset.open);
    if(b.dataset.editTask)openForm('task',b.dataset.editTask);
    if(b.dataset.filter){filter=b.dataset.filter;renderPlan();}
    if(b.dataset.planAdd){if(!planIds.includes(b.dataset.planAdd))planIds.push(b.dataset.planAdd);renderPlan();}
    if(b.dataset.planRemove!==undefined){planIds.splice(Number(b.dataset.planRemove),1);renderPlan();}
    if(b.dataset.planUp!==undefined){const i=Number(b.dataset.planUp);[planIds[i-1],planIds[i]]=[planIds[i],planIds[i-1]];renderPlan();}
    if(b.dataset.planDown!==undefined){const i=Number(b.dataset.planDown);[planIds[i+1],planIds[i]]=[planIds[i],planIds[i+1]];renderPlan();}
    if(b.id==='save-plan'){await api('/api/plan',{date:state.date,taskIds:planIds});await load();notice('오늘의 계획을 저장했습니다.');}
    if(b.dataset.report)await showReport(b.dataset.report);
    if(b.dataset.evidence){const e=await api('/api/evidence?id='+encodeURIComponent(b.dataset.evidence));modal('작업 근거',`<p class="hint">${esc(e.sourceRef||'')}</p><pre>${esc(e.text)}</pre>`);}
    if(b.dataset.logDay){worklogDay=b.dataset.logDay;renderWorklog();}
    if(b.id==='add-share'){$('#allocation-rows').insertAdjacentHTML('beforeend',allocationRow());allocationDirty=true;$('#post-worklog').disabled=true;}
    if(b.hasAttribute('data-remove-share')){b.closest('.allocation-row').remove();updateTotal();allocationDirty=true;$('#post-worklog').disabled=true;}
    if(b.id==='post-worklog'){b.disabled=true;await api('/api/worklogs/post',{date:worklogDay});await load();notice('워크로그를 Jira에 등록했습니다.');}
    if(b.dataset.member){selectedMember=b.dataset.member;renderTeamContent();}
    if(b.id==='sync-team'){b.disabled=true;await api('/api/sync',{kind:'team'});await renderTeam();notice('팀원별 Jira 업무와 등록 시간을 확인했습니다.');}
    if(b.dataset.reviewJob){const job=state.jobs.find(j=>j.id===b.dataset.reviewJob);await api('/api/jobs',{...job,reviewed:true});await load();notice('검토 완료로 표시했습니다.');}
    if(b.id==='copy-report'){await navigator.clipboard.writeText($('#report-text').textContent);notice('보고서 내용을 복사했습니다.');}
  }catch(e){notice(e.message);if(b.id==='post-worklog'){await load();}else b.disabled=false;}
});
document.addEventListener('change',async event=>{
  const el=event.target;
  try {
    if(el.dataset.taskCheck){const task=state.tasks.find(t=>t.id===el.dataset.taskCheck);await api('/api/tasks',{id:task.id,title:task.title,status:el.checked?'done':'open'});await load();}
    if(el.id==='selected-date'){worklogDay='';await load();}
  }catch(e){notice(e.message);}
});
document.addEventListener('input',event=>{if(event.target.closest('#allocation-form')){allocationDirty=true;$('#post-worklog').disabled=true;updateTotal();}});
document.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target;const submit=$('[type="submit"]',form);if(submit)submit.disabled=true;
  try {
    if(form.id==='record-form'){
      const kind=form.dataset.kind,data=Object.fromEntries(new FormData(form));
      if(form.dataset.id)data.id=form.dataset.id;
      if(kind==='task'){data.important=form.elements.important.checked;data.urgent=form.elements.urgent.checked;if(!data.product)data.product='미분류';}
      if(kind==='meeting'){data.start+=':00+09:00';if(data.end)data.end+=':00+09:00';}
      if(kind==='daily')data.date=state.date;
      if(kind==='observation')data.memberId=selectedMember;
      await api('/api/'+({task:'tasks',activity:'activities',meeting:'meetings',daily:'daily',member:'members',observation:'observations'}[kind]),data);
      $('#modal').close();await load();notice('저장했습니다.');
    }
    if(form.id==='allocation-form'){
      const shares=[...document.querySelectorAll('#allocation-rows .allocation-row')].map(row=>({jiraKey:$('.share-key',row).value.trim().toUpperCase(),percent:Number($('.share-percent',row).value),comment:$('.share-comment',row).value}));
      await api('/api/worklogs',{date:worklogDay,totalMinutes:Number($('#total-minutes').value),shares});await load();notice('배분 초안을 저장했습니다. Jira 등록은 별도로 실행하세요.');
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
$('#connections').addEventListener('click',()=>modal('연결 상태',`<div class="connection-row"><div><strong>Slack</strong><small>SLACK_APP_TOKEN · SLACK_BOT_TOKEN · SLACK_USER_ID</small></div><span class="${settings.slackConnected?'':'muted'}">${settings.slackConnected?'연결됨':settings.slackConfigured?'연결 확인 중':'설정 필요'}</span></div><div class="connection-row"><div><strong>Jira</strong><small>JIRA_URL · JIRA_EMAIL · JIRA_TOKEN_ENV에 지정한 환경변수</small></div><span>${settings.jiraConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>캘린더</strong><small>CALENDAR_ICS_URL 또는 CALENDAR_ICS_FILE</small></div><span>${settings.calendarConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>자연어 처리</strong><small>로그인한 ${esc(settings.engine)} CLI를 사용합니다.</small></div><span>${esc(settings.engine)}</span></div><div class="connection-row"><div><strong>정기 브리핑</strong><small>평일 ${esc(settings.briefingTime||'꺼짐')} · 금요일 워크로그 ${esc(settings.fridayTime||'꺼짐')}<br>Asia/Seoul · 프로그램이 실행 중일 때 동작합니다.</small></div></div><p class="hint">프로젝트의 config.properties에 값을 입력한 뒤 프로그램을 다시 시작하세요. 자세한 절차는 README.md에 있습니다.</p>${settings.slackError?`<p class="hint error">${esc(settings.slackError)}</p>`:''}`));
load().catch(e=>{$('#content').innerHTML=empty('업무 기록을 불러오지 못했습니다.',e.message);});
