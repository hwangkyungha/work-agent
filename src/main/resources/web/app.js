'use strict';
const $ = (s, root = document) => root.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
let state, settings, team, view = 'today', filter = 'open', planIds = [], selectedMember = '', worklogDay = '';
let productFilter='',workTypeFilter='';
let noticeTimer, projectFilter='', taskSearch='', taskSort='priority', todayStatus='open', draggedId='', savingPlan=false, dragOriginY=0, didDrag=false;
const selectedTasks=new Set(),expandedCandidates=new Set(['recent','due','priority']);
const collapsedWorkGroups=new Set();let groupToday=true;
const worklogEdits=new Map();let worklogFieldId=0;
let syncingWorklogs=false;const attemptedWorklogWeeks=new Set();
let reviewState, reviewPoll, reviewRender='';
let pastWorkCandidates=[],reviewDate='';
const workDayCache=new Map(),openLedgerDetails=new Set();
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
const allTasks=()=>[...state.tasks,...state.hiddenTasks];
function workGroup(task){return task.groupName??task.workGroup??(task.jiraParent?.key?[task.jiraParent.key,task.jiraParent.title].filter(Boolean).join(' '):'');}
function findTask(id){return allTasks().find(t=>t.id===id||(t.jiraKey&&'jira:'+t.jiraKey===id));}
function predecessors(task){
  if(Object.hasOwn(task,'predecessorIds'))return task.predecessorIds.map(id=>findTask(id)||{id,jiraKey:id.startsWith('jira:')?id.slice(5):'',title:'목록 밖의 작업',status:'unknown'});
  return (task.jiraPredecessors||[]).map(p=>allTasks().find(t=>t.jiraKey===p.key)||{id:'jira:'+p.key,jiraKey:p.key,title:p.title||p.key,status:p.status});
}
function dependencyInfo(task){
  const previous=predecessors(task);if(!previous.length)return '';
  const complete=previous.every(t=>t.status==='done'),waiting=task.status!=='done'&&!complete;
  return `<div class="dependency-line ${waiting?'waiting':''}"><span>${complete?'선행 완료':waiting?'선행 작업 대기':'선행 작업'}</span>${previous.map(t=>`<span>${t.jiraKey?issueLink(t.jiraKey,t.issueUrl):''} ${findTask(t.id)?`<button type="button" class="dependency-title" data-edit-task="${esc(t.id)}">${esc(t.title)}</button>`:esc(t.title===t.jiraKey?'':t.title)}${t.hidden?' · 숨긴 작업':''}</span>`).join(' · ')}</div>`;
}
function dependencyOrder(tasks){
  const result=[],done=new Set(),visiting=new Set(),ids=new Set(tasks.map(t=>t.id));
  function visit(task){if(done.has(task.id)||visiting.has(task.id))return;visiting.add(task.id);for(const previous of predecessors(task))if(ids.has(previous.id))visit(previous);visiting.delete(task.id);done.add(task.id);result.push(task);}
  tasks.forEach(visit);return result;
}
function workGroupsHtml(tasks,row,today=false){
  if(!tasks.some(t=>workGroup(t)))return (today?tasks:dependencyOrder(tasks)).map(row).join('');
  const groups=new Map();for(const task of tasks){const name=workGroup(task);if(!groups.has(name))groups.set(name,[]);groups.get(name).push(task);}
  return [...groups].map(([name,items])=>{
    const key=view+':'+name,whole=allTasks().filter(t=>!!t.hidden===(!today&&filter==='hidden')&&workGroup(t)===name),done=whole.filter(t=>t.status==='done').length;
    return `<details class="work-group" data-work-group="${esc(key)}" ${collapsedWorkGroups.has(key)?'':'open'}><summary><span class="work-group-name">${esc(name||'묶음 없는 업무')}</span><span class="work-group-progress">완료 ${done} / 전체 ${whole.length}${today?' · 오늘 '+items.length+'건':items.length!==whole.length?' · 표시 '+items.length+'건':''}</span></summary><div class="work-group-list">${(today?items:dependencyOrder(items)).map(row).join('')}</div></details>`;
  }).join('');
}
function groupField(value=''){
  const groups=[...new Set(allTasks().map(workGroup).filter(Boolean))].sort();
  return `<div class="field"><label for="field-workGroup">업무 묶음</label><input id="field-workGroup" name="workGroup" list="work-group-names" maxlength="100" value="${esc(value)}" placeholder="예: CI 리포트 개선"><datalist id="work-group-names">${groups.map(name=>`<option value="${esc(name)}"></option>`).join('')}</datalist></div>`;
}
function workTypes(){return settings?.workTypes||[];}
function filterWorkTypes(){return [...new Set([...workTypes(),...allTasks().map(t=>t.workType).filter(Boolean)])];}
function workTypeField(value=''){return `<div class="field"><label for="field-workType">업무 성격</label><select id="field-workType" name="workType"><option value="">미분류</option>${[...new Set([...workTypes(),value].filter(Boolean))].map(name=>`<option ${value===name?'selected':''}>${esc(name)}</option>`).join('')}</select></div>`;}
function structureFields(task){
  const previous=task?predecessors(task):[],selected=new Set(previous.map(t=>t.id)),choices=[...allTasks().filter(t=>t.id!==task?.id&&(!t.hidden||selected.has(t.id))),...previous.filter(t=>!findTask(t.id))];
  return workTypeField(task?.workType||'')+groupField(task?workGroup(task):'')+`<details class="predecessor-picker" ${previous.length?'open':''}><summary>선행 작업 <span>${previous.length}건</span></summary><p class="hint">먼저 끝나야 하는 작업만 선택하세요</p><input type="search" id="predecessor-search" placeholder="이슈 번호 또는 작업 제목" aria-label="선행 작업 검색"><div class="predecessor-options">${choices.map(t=>`<label data-predecessor-option><input type="checkbox" name="predecessorIds" value="${esc(t.id)}" ${selected.has(t.id)?'checked':''}><span>${esc([t.jiraKey,t.title].filter(Boolean).join(' '))}<small>${esc(statusName(t.status))}${t.hidden?' · 숨긴 작업':''}</small></span></label>`).join('')||'<p class="hint">선택할 다른 작업이 없습니다</p>'}</div></details>`;
}
async function openStatus(task){
  const linked=!!task.jiraKey;let options;
  if(linked){const data=await api('/api/tasks/transitions?id='+encodeURIComponent(task.id));options=`<option value="">${esc(data.current.jiraStatus)}</option>`+data.transitions.map(t=>{const required=Object.values(t.fields||{}).some(f=>f.required&&!f.hasDefaultValue&&f.defaultValue==null);return `<option value="${esc(t.id)}" ${required?'disabled':''}>${esc(t.to.name)}${required?' · Jira에서 추가 입력 필요':''}</option>`;}).join('');}
  else options=['todo','in-progress','done'].map(v=>`<option value="${v}" ${task.status===v?'selected':''}>${v}</option>`).join('');
  modal('상태 변경',`<p class="status-task-name">${esc(task.title)}</p><label for="status-choice">${linked?'Jira 상태':'업무 상태'}</label><select id="status-choice" class="task-status" data-task-status="${esc(task.id)}" data-remote="${linked}">${options}</select>`);
  $('#status-choice').focus();
}
function taskRow(task,index,controls='') {
  const createdSort=(view==='today'?state.planSort:taskSort)?.startsWith('created-');
  return `<div class="task-row ${task.status==='done'?'done':''} ${predecessors(task).length?'has-predecessors':''}"><div class="task-detail"><div class="task-heading"><button type="button" class="task-title" data-edit-task="${esc(task.id)}" aria-label="${esc(task.title)} 수정">${esc(task.title)}</button><button type="button" class="status-button status-${esc(task.status)}" data-open-status="${esc(task.id)}" aria-label="${esc(task.title)} 상태 변경" ${task.hidden?'disabled':''}><span class="status-dot"></span>${esc(task.jiraStatus||statusName(task.status))}</button></div><small><span>${esc(task.product||'미분류')}</span>${task.workType?`<span>${esc(task.workType)}</span>`:''}${issueLink(task.jiraKey,task.issueUrl)}${task.lastWorkedAt?`<span>최근 작업 ${esc(shortDay(task.lastWorkedDate||task.lastWorkedAt.slice(0,10)))}</span>`:''}${task.due?`<span class="${task.due<state.date?'error':''}">마감 ${esc(task.due)}</span>`:''}${createdSort&&task.createdAt?`<span>생성 ${esc(new Date(task.createdAt).toLocaleDateString('ko-KR',{timeZone:'Asia/Seoul'}))}</span>`:''}${task.important?'<span class="tag blue">중요</span>':''}${task.urgent?'<span class="tag amber">긴급</span>':''}</small>${dependencyInfo(task)}</div>${controls}</div>`;
}
function groupedActivities(groups) {
  if(!groups.length)return empty('수집된 작업 근거가 없습니다.','필요한 Jira 이슈와 시간을 직접 입력할 수 있습니다.');
  return groups.map(g=>`<article class="activity-group"><h3>${issueLink(g.jiraKey)} ${esc(g.title===g.jiraKey?'':g.title)}</h3>${g.summary?`<p>${esc(g.summary)}</p>`:''}<small>메시지 ${g.messageCount}개 (사용자 ${g.userMessages} / 응답 ${g.assistantMessages}) · 세션 ${g.sessionCount}개${g.activityCount?' · 활동 기록 '+g.activityCount+'건':''}</small>${g.sessions?.length?`<details><summary>세션 보기</summary>${g.sessions.map(x=>`<p>${esc(sourceName(x.source))} · ${esc(x.name)} <small>메시지 ${x.messageCount}개</small></p>`).join('')}</details>`:''}</article>`).join('');
}
async function load() {
  const date=$('#selected-date').value;
  [state,settings]=await Promise.all([api('/api/state'+(date?'?date='+date:'')),api('/api/settings')]);
  workDayCache.clear();
  if(reviewDate&&reviewDate!==state.date&&reviewDate!==state.previousWorkDate)await fetchWorkDay(reviewDate);
  if(view==='worklog'&&worklogDay&&worklogDay!==state.date&&worklogDay!==state.previousWorkDate&&openLedgerDetails.has('worklog-review'))await fetchWorkDay(worklogDay);
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
  if(view==='reviews')renderReviews();else clearTimeout(reviewPoll);
}
const reviewStatus={queued:'대기 중',running:'검토 중',ready:'검토 완료',saving:'비공개 초안 저장 중',saved:'비공개 초안 저장됨',failed:'실패',stale:'내용 변경으로 재검토 필요','delivery-unknown':'댓글 저장 결과 확인 필요'};
function prLink(url,label='Bitbucket에서 보기'){
  try{const target=new URL(url);if(target.protocol==='https:'&&target.hostname==='bitbucket.org')return `<a href="${esc(target.href)}" target="_blank" rel="noopener noreferrer">${esc(label)}</a>`;}catch{}
  return '';
}
async function renderReviews(){
  clearTimeout(reviewPoll);reviewRender='';
  $('#content').innerHTML=heading('PR 리뷰','나에게 온 리뷰 요청과 내 PR의 댓글을 모든 저장소에서 확인합니다.','<button class="button" id="refresh-reviews">리뷰 요청 새로 확인</button>')+`<section class="sheet"><div class="sheet-body"><form id="review-request" class="review-request"><label for="pr-url">PR 주소</label><input id="pr-url" name="url" type="url" placeholder="https://bitbucket.org/workspace/repository/pull-requests/123" required><button class="button primary" type="submit">리뷰 요청</button><details class="review-references"><summary>추가 외부 문서 · 이번 검토만</summary><label for="pr-references">Bitbucket 문서 주소 · 최대 4개</label><textarea id="pr-references" name="references" rows="3" placeholder="관련 명세가 다른 저장소에 있다면 주소를 한 줄에 하나씩 입력하세요."></textarea></details></form><p class="hint">댓글은 비공개 초안으로 저장합니다. Bitbucket에서 확인한 뒤 리뷰를 제출하세요.</p></div></section><section class="sheet"><div class="sheet-body"><details><summary>저장소 연결</summary><p class="hint">기본은 로컬 프로젝트에서 검토하고 내 PR 댓글은 연결된 작업 세션을 활용합니다. 로컬 저장소를 자동으로 찾지 못할 때 경로를 지정하세요. 외부 명세는 선택 사항입니다.</p><form id="review-repository"><div class="field"><label for="review-repository-name">저장소</label><input id="review-repository-name" name="repository" placeholder="workspace/repository" required></div><div class="field"><label for="review-local-path">로컬 저장소 경로</label><input id="review-local-path" name="localPath" placeholder="예: D:/Git/lct"><small>자동으로 찾지 못하거나 같은 저장소가 여러 개일 때 지정하세요.</small></div><details class="external-review-sources"><summary>추가 외부 명세 · 자동·수동 검토 공통</summary><p class="hint">프로젝트 밖의 인터페이스 계약 등을 함께 확인해야 할 때만 연결합니다. 기존 연결은 유지되며 PR마다 다시 입력할 필요가 없습니다.</p><div class="field"><label for="review-repository-sources">외부 참고 위치 · 최대 4개</label><textarea id="review-repository-sources" name="sources" rows="4" placeholder="한 줄에 한 위치씩 입력하세요. 비워서 저장하면 연결을 해제합니다."></textarea></div></details><button class="button" type="submit">저장</button></form><div id="review-repositories"></div></details></div></section><div id="review-status"></div><div id="review-list"></div>`;
  await updateReviews();
}
function reviewDetailKey(detail){return [detail.closest('[data-review-id]')?.dataset.reviewId,detail.closest('[data-finding-id]')?.dataset.findingId||'job',detail.querySelector('summary')?.textContent.replace(/\d+/g,'#')].join(':');}
async function updateReviews(){
  clearTimeout(reviewPoll);
  try{
    reviewState=await api('/api/reviews');if(view!=='reviews')return;
    $('#review-status').innerHTML=`<p class="hint">${reviewState.scanning?'저장소 확인 중…':reviewState.at?`${esc((reviewState.workspaces||[]).join(', '))} · 저장소 ${Number(reviewState.repositoryCount||0)}개 확인 · ${esc(clock(reviewState.at))}`:reviewState.configured?'아직 자동 조회 전입니다.':'Bitbucket 이메일과 토큰을 설정해 주세요.'} · 자동 확인 ${reviewState.automatic?'켜짐 (한국 시간 08:00~16:30, 30분 간격)':'꺼짐'}</p>${[...(reviewState.warnings||[]),reviewState.error].filter(Boolean).map(text=>`<p class="hint error">${esc(text)}</p>`).join('')}`;
    $('#review-repositories').innerHTML=[...(reviewState.repositories||[]),...(reviewState.localRepositories||[]).filter(local=>!(reviewState.repositories||[]).some(row=>row.repository===local.repository))].map(row=>`<p><strong>${esc(row.repository)}</strong> <button class="text-button" data-review-repository="${esc(row.repository)}">편집</button><br><small>로컬: ${esc((reviewState.localRepositories||[]).find(local=>local.repository===row.repository)?.localPath||'자동 검색')}<br>외부 명세: ${(row.sources||[]).map(esc).join('<br>')||'사용 안 함'}</small></p>`).join('');
    const jobs=reviewState.jobs||[],signature=JSON.stringify(jobs);
    if(reviewRender===signature)return;
    const opened=new Set([...document.querySelectorAll('#review-list details[open]')].map(reviewDetailKey));
    $('#review-list').innerHTML=jobs.length?jobs.map(job=>{
      const items=job.kind==='reply'?(job.reply?[{id:'reply',comment:job.reply,evidence:job.replyEvidence||[]}]:[]):job.findings||[];
      return `<section class="sheet review-card" data-review-id="${esc(job.id)}"><div class="sheet-header"><div><small>${job.validation?'검증용 · Slack 알림 없음 · ':''}${job.kind==='reply'?'내 PR 댓글 답변':'요청받은 PR 리뷰'} · ${esc(reviewStatus[job.status]||job.status)}</small><h2>${esc(job.title)}</h2></div>${prLink(job.url)}</div><div class="sheet-body"><p class="hint">head ${esc(job.sourceCommit?.slice(0,12))}${job.totalBatches?` · ${Number(job.checkedBatches||0)}/${Number(job.totalBatches)}개 묶음 확인`:''}</p>${job.referenceUrls?.length?`<p class="hint">${job.referenceUrls.map((url,index)=>prLink(url,'참고 문서 '+(index+1))).join(' · ')}</p>`:''}${job.referenceSnapshots?.length?`<details><summary>사용한 저장소 명세 ${job.referenceSnapshots.length}개</summary>${job.referenceSnapshots.map(ref=>`<p class="hint">${esc(ref.source)}<br>${esc(ref.path.split('/').slice(-2).join('/'))} · 버전 ${esc(ref.commit.slice(0,12))}<br>SHA-256 ${esc(ref.sha256)}</p>`).join('')}</details>`:''}${job.executionMode?`<p class="hint">${job.executionMode==='project-session'?'프로젝트 세션에서 검토':'발췌 자료로 검토'}${job.originSession?' · 원래 작업 세션 연결됨':''}</p>`:''}${job.executionNote?`<p class="hint">${esc(job.executionNote)}</p>`:''}${job.kind==='reply'?`<button class="text-button" data-bind-pr-session="${esc(job.url)}">원래 작업 세션 연결</button>`:''}${job.readingGuide?`<details open><summary>PR 이해하기</summary><p class="review-comment">${esc(job.readingGuide)}</p></details>`:''}${job.summary?`<p class="review-summary">${esc(job.summary)}</p>`:''}${job.error?`<p class="hint error">${esc(job.error)}</p>`:''}${items.map(item=>`<article class="review-finding" data-finding-id="${esc(item.id)}">${item.path?`<h3>${esc(item.path)}:${Number(item.line)} · ${esc(item.title)}</h3>`:'<h3>답변 초안</h3>'}<p class="review-comment">${esc(item.comment)}</p>${item.expected?`<details><summary>발생 조건과 판단 근거</summary><p><strong>발생 조건</strong> ${esc(item.trigger)}</p><p><strong>기대 결과</strong> ${esc(item.expected)}</p><p><strong>실제 동작</strong> ${esc(item.actual)}</p><p><strong>판단 근거</strong> ${esc(item.reasoning)}</p></details>`:''}${job.status==='ready'?`<button class="text-button" data-edit-review="${esc(job.id)}" data-finding-id="${esc(item.id)}">초안 편집</button>`:''}${item.evidence?.length?`<details><summary>코드 근거 ${item.evidence.length}개</summary>${item.evidence.map(e=>`<p class="hint">${esc(e.path)}:${Number(e.line)} · ${e.side==='old'?'변경 전':'현재 코드'}</p><pre>${esc(e.quote)}</pre>`).join('')}</details>`:''}</article>`).join('')}${!items.length&&job.status==='ready'?'<p class="hint">확실한 결함이나 답변 근거가 없어 댓글을 작성하지 않았습니다.</p>':''}${job.limitations?.length?`<details><summary>검토 범위와 한계</summary><ul>${job.limitations.map(text=>`<li>${esc(text)}</li>`).join('')}</ul></details>`:''}${job.status==='ready'&&items.length&&!job.validation?`<div class="form-actions"><button class="button primary" data-save-review="${esc(job.id)}">Bitbucket 비공개 초안 저장</button></div>`:''}${job.status==='delivery-unknown'?`<p class="hint error">Bitbucket에서 댓글을 확인해 주세요. 중복 저장을 막기 위해 새 댓글을 바로 보내지 않습니다.</p><button class="button" data-save-review="${esc(job.id)}">저장 결과 다시 확인</button>`:''}${job.status==='failed'||job.status==='stale'?`<button class="button" data-retry-review="${esc(job.id)}">다시 검토</button>`:''}</div></section>`;
    }).join(''):empty('아직 리뷰가 없습니다.','리뷰 요청을 새로 확인하거나 PR 주소를 입력하세요.');
    reviewRender=signature;document.querySelectorAll('#review-list details').forEach(detail=>{if(opened.has(reviewDetailKey(detail)))detail.open=true;});
  }catch(e){if(view==='reviews')notice(e.message);}
  finally{if(view==='reviews')reviewPoll=setTimeout(()=>{if(!document.hidden&&!$('#modal').open)updateReviews();else reviewPoll=setTimeout(updateReviews,10000);},10000);}
}
function registeredOn(day){return (state.registeredWorklogs||[]).filter(row=>row.date===day);}
function registeredSeconds(day){return registeredOn(day).reduce((sum,row)=>sum+row.seconds,0);}
async function syncWorklogs(manual=false){
  if(syncingWorklogs||!settings.jiraConfigured)return;
  const date=state.date,week=monday(date);syncingWorklogs=true;attemptedWorklogWeeks.add(week);
  if(view==='worklog')renderWorklog();
  try{await api('/api/worklogs/sync',{date});await load();if(manual)notice('선택한 주의 Jira 워크로그를 확인했습니다.');}
  catch(e){if(manual)notice(e.message);else notice('Jira 워크로그를 읽지 못했습니다. 입력한 초안은 유지됩니다.');try{await load();}catch{}}
  finally{syncingWorklogs=false;if(view==='worklog')renderWorklog();}
}
function priority(task){return (task.urgent?0:2)+(task.important?0:1);}
function sortTasks(tasks,sort){return [...tasks].sort((a,b)=>{
  if(sort.startsWith('created-')){const first=Date.parse(a.createdAt),second=Date.parse(b.createdAt);if(!Number.isFinite(first))return Number.isFinite(second)?1:0;if(!Number.isFinite(second))return -1;return sort==='created-desc'?second-first:first-second;}
  return sort==='due'?(a.due||'9999').localeCompare(b.due||'9999')||priority(a)-priority(b):sort==='priority'?priority(a)-priority(b)||(a.due||'9999').localeCompare(b.due||'9999'):0;
});}
function todayList(){return state.plan.filter(t=>todayStatus==='all'||(todayStatus==='done'?t.status==='done':t.status!=='done'));}
function todayRow(task){return `<div class="today-task" data-today-id="${esc(task.id)}"><button class="drag-handle" data-drag-task="${esc(task.id)}" aria-label="${esc(task.title)} 순서 이동" title="드래그하여 이동 · 키보드 Alt+위/아래">⠿</button>${taskRow(task,0,`<div class="row-actions"><button class="icon-button today-remove" data-today-remove="${esc(task.id)}" aria-label="${esc(task.title)} 오늘 할 일에서 제외" title="오늘 할 일에서 제외">×</button></div>`)}</div>`;}
function sideSheet(title,actions,content,blank=false){
  const header=`<h2>${title}</h2><div class="side-sheet-actions">${actions}</div>`;
  return blank?`<details class="sheet empty-sheet"><summary class="sheet-header">${header}</summary><div class="sheet-body">${content}</div></details>`:`<section class="sheet"><div class="sheet-header">${header}</div><div class="sheet-body">${content}</div></section>`;
}
function proposalButton(proposal){const task=proposal?.acceptedTaskId?findTask(proposal.acceptedTaskId):null,selected=task&&(task.status==='done'||planIds.includes(task.id));return proposal&&!proposal.dismissed?`<button class="button" data-accept-followup="${esc(proposal.id)}" ${selected?'disabled':''}>${selected?(task.status==='done'?'완료한 업무':'오늘 등록됨'):'오늘 할 일에 추가'}</button>${!proposal.acceptedTaskId&&proposal.kind==='followup'?`<button class="text-button" data-dismiss-followup="${esc(proposal.id)}">제안 제외</button>`:''}`:'';}
function draftResultsSheet(){
  const digest=state.draftDigest||{},prs=(digest.prs||[]).filter(row=>!row.reviewed),reviewed=(digest.prs||[]).filter(row=>row.reviewed),jobs=state.jobs.filter(job=>!job.reviewed);
  const timestamp=value=>new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(value));
  const period='매일 오전 8시에 모든 열린 내 draft PR을 확인합니다. 미확인 결과와 확인 후 변경된 head는 날짜와 관계없이 남습니다.';
  const cards=prs.map(row=>`<article class="job-row draft-result"><div class="draft-result-heading"><h3>${esc(row.title)}</h3><span class="tag">${row.reviewed?'검토 완료':'검토 대기'}</span></div><small>${(row.issueLinks||[]).map(issue=>issueLink(issue.key,issue.url)).join(' ')} · ${prLink(row.url,'PR #'+row.number+' 열기')}</small>${row.summary?`<p><strong>PR 설명 요약</strong> ${esc(row.summary)}</p>`:''}<div class="form-actions">${proposalButton(row.reviewSuggestion)}<button class="text-button" data-draft-reviewed="${esc(row.id)}" data-reviewed="${!row.reviewed}">${row.reviewed?'검토 대기로':'검토 완료로 표시'}</button></div>${(row.suggestions||[]).map(proposal=>`<div class="draft-next-task"><strong>${esc(proposal.title)}</strong><p>${esc(proposal.reason)}</p>${proposalButton(proposal)}</div>`).join('')}</article>`).join('');
  const visibleIds=new Set(prs.flatMap(row=>(row.suggestions||[]).map(proposal=>proposal.id))),remaining=(state.followups||[]).filter(proposal=>proposal.sourceDraftResultId&&!visibleIds.has(proposal.id));
  const nextTasks=remaining.length?`<h3 class="result-subtitle">남은 후속 작업</h3>${remaining.map(proposal=>`<article class="draft-next-task"><strong>${esc(proposal.title)}</strong><p>${esc(proposal.reason)}</p>${prLink(proposal.sourcePrUrl,'관련 PR')}<div class="form-actions">${proposalButton(proposal)}</div></article>`).join('')}`:'';
  const manual=jobs.length?'<h3 class="result-subtitle">직접 등록한 작업 결과</h3>'+jobs.map(job=>`<article class="job-row"><h3>${esc(job.title)}</h3><p>${esc(job.result)}</p><small>${esc(job.verification||'검증 결과가 아직 없습니다.')}</small>${job.artifact?`<small>결과물: ${esc(job.artifact)}</small>`:''}<button class="text-button" data-review-job="${esc(job.id)}">검토 완료로 표시</button></article>`).join(''):'';
  return sideSheet('작업 결과와 다음 할 일','<button class="text-button" id="refresh-draft-results">다시 정리</button>',`<p class="hint">${esc(period)}</p>${(digest.errors||[]).map(error=>`<p class="hint error">${esc(error)}</p>`).join('')}${cards||`<p class="hint">${digest.generatedAt?((digest.errors||[]).length?'일부 자료를 확인하지 못했습니다. 다시 정리해서 확인해 주세요.':'아직 확인하지 않은 draft PR이 없습니다.'):'아직 정리하지 않았습니다. 다시 정리 버튼으로 지금 확인할 수 있습니다.'}</p>`}${reviewed.length?`<details><summary>확인한 열린 Draft PR ${reviewed.length}건</summary>${reviewed.map(row=>`<p>${prLink(row.url,row.title)} <button class="text-button" data-draft-reviewed="${esc(row.id)}" data-reviewed="false">다시 검토 대기로</button></p>`).join('')}</details>`:''}${nextTasks}${manual}`);
}
const outcomeNames={unconfirmed:'확인 전', 'not-started':'진행하지 않음',progress:'진행 중',done:'완료',deferred:'보류',cancelled:'취소'};
async function openPastWork(date=state.previousWorkDate){
  pastWorkCandidates=await api('/api/work-days/candidates?date='+encodeURIComponent(date));
  const choices=allTasks();
  modal(shortDay(date)+' 수행한 일 추가',`<p class="hint">계획에 없던 업무만 추가하세요. 기록은 대화가 많았던 순서입니다. Jira 상태와 오늘 계획은 변경하지 않습니다.</p><details class="past-work-records" open><summary>작업 기록에서 선택 <span>${pastWorkCandidates.length}건</span></summary><label class="sr-only" for="past-work-search">작업 기록 검색</label><input type="search" id="past-work-search" placeholder="이슈 번호 또는 작업 제목"><div class="past-work-candidates">${pastWorkCandidates.map(row=>`<article class="past-work-candidate" data-past-work-option><div><strong>${esc(row.title)}</strong><small>${issueLink(row.jiraKey,row.issueUrl)} · 메시지 ${Number(row.evidence?.messageCount||0)}개${row.taskId?' · 기존 할 일과 연결됨':''}</small></div><button type="button" class="button" data-choose-past-work="${esc(row.workId)}">선택</button></article>`).join('')||'<p class="hint">추가로 확인한 작업 기록이 없습니다. 아래에서 업무를 선택하거나 직접 입력하세요.</p>'}</div></details><form id="past-work-form" data-date="${esc(date)}"><div class="field"><label for="past-work-task">기존 할 일 선택</label><select id="past-work-task" name="taskId" data-task-picker><option value="">직접 입력 또는 선택한 작업 기록</option>${choices.map(task=>`<option value="${esc(task.id)}">${esc([task.jiraKey,task.title].filter(Boolean).join(' '))}</option>`).join('')}</select></div><div class="field"><label for="past-work-title">수행한 일</label><input id="past-work-title" name="title" required maxlength="300" placeholder="기록이 없는 업무도 직접 입력하세요"><button type="button" class="text-button" id="reset-past-work">직접 새로 입력</button></div><div class="field"><label for="past-work-outcome">${esc(shortDay(date))} 수행 상태</label><select id="past-work-outcome" name="outcome"><option value="progress">진행 중</option><option value="done">완료</option></select></div><div class="form-actions"><button type="submit" class="button primary">수행한 일에 추가</button></div></form>`);
}
function workDayRows(date){
  if(date===state.date)return state.todayWork||[];
  if(date===state.previousWorkDate)return state.previousWork||[];
  return workDayCache.get(date)||[];
}
async function fetchWorkDay(date){
  if(date!==state.date&&date!==state.previousWorkDate)workDayCache.set(date,await api('/api/work-days?date='+encodeURIComponent(date)));
}
function workReviewSheet(date=reviewDate||state.date,context='today'){
  const rows=workDayRows(date),key=context+'-review',planned=rows.filter(row=>row.planned).length,done=rows.filter(row=>row.outcome==='done').length;
  return `<details class="sheet work-reconciliation" data-review-date="${esc(date)}" data-ledger-detail="${key}" ${openLedgerDetails.has(key)?'open':''}><summary class="review-summary"><span><strong>계획과 실제</strong><small>${esc(shortDay(date))} · 계획 ${planned}건 · 완료 ${done}건</small></span><span class="review-summary-action">수행 정리</span></summary><div class="sheet-body">${context==='today'?`<div class="review-date-toolbar"><div class="list-filters"><button class="filter ${date===state.date?'active':''}" data-review-day="${state.date}">오늘</button><button class="filter ${date===state.previousWorkDate?'active':''}" data-review-day="${state.previousWorkDate||dayAdd(state.date,-1)}">이전 근무일</button></div><label>날짜 <input type="date" id="work-review-date" value="${esc(date)}" max="${state.date}" aria-label="수행 정리 날짜"></label></div>`:''}<div class="review-intro"><p class="hint">계획과 실제 수행을 정리하면 이날의 워크로그에 반영됩니다. Jira 상태는 바꾸지 않습니다.</p><button class="button" id="add-previous-work" data-add-actual-date="${esc(date)}">수행한 일 추가</button></div>${rows.map(row=>`<article class="work-day-row"><div><h3>${row.taskId&&findTask(row.taskId)?`<button class="task-title" data-edit-task="${esc(row.taskId)}">${esc(row.title)}</button>`:esc(row.title)}</h3><small>${issueLink(row.jiraKey,row.issueUrl)} ${row.planned?(row.inInitialPlan?'처음 정한 계획':'업무 중 추가한 계획'):'계획 밖 활동'}${row.planned&&!row.inFinalPlan?' · 목록에서 제외됨':''}${row.priorityChanged?' · 중요도/긴급도 변경':''}</small>${row.sourcePrUrl?`<p>${prLink(row.sourcePrUrl,'작성한 Draft PR')}</p>`:''}${row.note?`<p>${esc(row.note)}</p>`:''}${row.evidence?`<details class="work-evidence" data-ledger-detail="evidence:${esc(date)}:${esc(row.workId)}" ${openLedgerDetails.has('evidence:'+date+':'+row.workId)?'open':''}><summary>작업 근거 · 메시지 ${Number(row.evidence.messageCount||0)}개</summary>${groupedActivities([row.evidence])}</details>`:''}</div><div class="work-day-actions"><select data-work-outcome="${esc(row.workId)}" data-date="${esc(date)}" aria-label="${esc(row.title)} 수행 상태">${Object.entries(outcomeNames).map(([key,name])=>`<option value="${key}" ${row.outcome===key?'selected':''}>${name}</option>`).join('')}</select>${!row.taskId?`<button class="text-button" data-link-work="${esc(row.workId)}" data-date="${esc(date)}">업무에 연결</button>`:''}</div></article>`).join('')||'<p class="hint review-empty">등록한 계획이나 수행 내용이 없습니다. 수행한 일을 추가하세요.</p>'}</div></details>`;
}
function carryoverHtml(){return (state.carryover||[]).length?`<details class="candidate-group" open><summary>이어서 할 일 <span class="tag">${state.carryover.length}건</span></summary>${state.carryover.map(row=>`<article class="carryover-row"><div><strong>${esc(row.title)}</strong><small>${issueLink(row.jiraKey,row.issueUrl)}</small><p class="hint">${esc(row.date)} · ${esc(row.reason)}</p></div><button class="button" data-today-add="${esc(row.taskId)}">오늘 추가</button></article>`).join('')}</details>`:'';}
function renderToday() {
  const tasks=todayList();
  $('#content').innerHTML=heading('오늘 할 일','오늘 집중할 업무를 고르고, 진행하면서 조정하세요',`<button class="button" data-report="briefing">데일리 브리핑</button><button class="button" data-open="task" data-for-today="true">할 일 등록</button>`)+`
  <div class="today-grid"><div class="today-column"><section class="sheet today-sheet"><div class="sheet-header"><h2>오늘 할 일 <span class="tag blue">${state.plan.filter(t=>t.status!=='done').length}건</span></h2><div class="today-plan-actions"><button class="text-button" id="clear-today" ${planIds.length?'':'disabled'}>목록 비우기</button><span class="save-hint" role="status" id="plan-save-state">${state.planSaved?'자동 저장됨':'선택 대기'}</span></div></div><div class="sheet-body">
  <form id="quick-task-form" class="quick-task"><label class="sr-only" for="quick-task-title">오늘 할 일 추가</label><input id="quick-task-title" name="title" placeholder="오늘 할 일을 입력하고 Enter" required maxlength="300"><button class="button primary" type="submit">추가</button></form>
  <div class="task-toolbar"><div class="list-filters">${[['open','할 일'],['done','오늘 마친 일'],['all','전체']].map(([key,label])=>`<button class="filter ${todayStatus===key?'active':''}" data-today-status="${key}">${label}</button>`).join('')}</div><label>정렬 <select id="today-sort" aria-label="오늘 할 일 정렬">${[['priority','중요도·긴급도순'],['due','마감일순'],['created-desc','생성일 최신순'],['created-asc','생성일 오래된 순'],['manual','직접 정한 순서']].map(([v,l])=>`<option value="${v}" ${state.planSort===v?'selected':''}>${l}</option>`).join('')}</select></label></div>
  <div class="today-view-options"><p class="hint drag-hint">손잡이로 우선순위 조정 · 제목을 눌러 선행 작업 편집</p><label><input id="group-today" type="checkbox" ${groupToday?'checked':''}>묶음으로 보기</label></div>
  <div id="today-list">${tasks.length?(groupToday?workGroupsHtml(tasks,todayRow,true):tasks.map(todayRow).join('')):empty(todayStatus==='done'?'아직 마친 업무가 없습니다.':'오늘 할 일을 추가해 보세요.','아래에서 기존 업무를 선택하거나 위 입력란에 새 업무를 적으세요.')}</div>
  </div></section>${workReviewSheet()}<section class="sheet"><div class="sheet-header"><h2>오늘 할 일에 추가하기</h2></div><div class="sheet-body today-candidates">${carryoverHtml()}${state.todayGroups.map(group=>`<details class="task-group candidate-group" data-candidate-group="${esc(group.id)}" ${expandedCandidates.has(group.id)?'open':''}><summary>${esc(group.title)} <span class="tag">${group.tasks.length}건</span></summary><p class="hint candidate-description">${esc(group.description)}</p>${group.tasks.length?group.tasks.map(task=>taskRow(task,0,`<button class="button" data-today-add="${esc(task.id)}">오늘 추가</button>`)).join(''):'<p class="hint candidate-empty">조건에 맞는 업무가 없습니다.</p>'}</details>`).join('')}<div class="form-actions"><button class="text-button" data-go="plan">전체 할 일에서 다른 업무 찾기</button></div></section></div><div class="side-stack">${draftResultsSheet()}
  ${sideSheet('회의 일정','<button class="text-button" data-open="meeting">일정 추가</button>',state.meetings.length?state.meetings.map(m=>`<div class="schedule-row"><time>${m.allDay?'종일':clock(m.start)}</time><div><strong>${esc(m.title)}</strong><small>${esc(m.product||'미분류')}</small></div></div>`).join(''):'<p class="hint">등록된 회의가 없습니다</p>',!state.meetings.length)}
  <section class="sheet"><div class="sheet-header"><h2>데일리 미팅 자료</h2><button class="text-button" data-report="daily">자료 보기</button></div><div class="sheet-body"><p class="hint">최근 진행한 일과 오늘 계획을 모읍니다. 문제·도움 요청·결정할 사항이 있으면 덧붙이세요.</p><button class="button daily-add" data-open="daily">공유 내용 작성</button></div></section></div></div>`;
}
function filteredTasks(){const source=filter==='hidden'?state.hiddenTasks:state.tasks;return sortTasks(source.filter(t=>(filter==='hidden'||filter==='all'||(filter==='done'?t.status==='done':t.status!=='done'))&&(!projectFilter||(t.jiraKey?t.jiraKey.split('-')[0]:'local')===projectFilter)&&(!productFilter||t.product===productFilter)&&(!workTypeFilter||t.workType===workTypeFilter)&&(!taskSearch||(t.title+' '+t.jiraKey+' '+workGroup(t)).toLowerCase().includes(taskSearch.toLowerCase()))),taskSort);}
function selectableTask(t){return `<div class="selectable-task"><input type="checkbox" data-select-task="${esc(t.id)}" aria-label="${esc(t.title)} 선택" ${selectedTasks.has(t.id)?'checked':''}>${taskRow(t,0,`<div class="row-actions">${!t.hidden&&t.status!=='done'?`<button data-today-toggle="${esc(t.id)}">${planIds.includes(t.id)?'오늘에서 제외':'오늘 추가'}</button>`:''}<details class="task-menu"><summary aria-label="${esc(t.title)} 더보기">···</summary><div><button data-edit-task="${esc(t.id)}">업무 편집</button><button data-hide-task="${esc(t.id)}" class="${t.jiraKey?'':'delete-task'}">${t.jiraKey?(t.hidden?'복원':'숨기기'):'삭제'}</button></div></details></div>`)}</div>`;}
function taskListHtml(visible){return visible.length?workGroupsHtml(visible,selectableTask):empty('조건에 맞는 업무가 없습니다.','프로젝트나 검색 조건을 바꿔 보세요.');}
function updateTaskList(){
  const visible=filteredTasks();$('#all-task-list').innerHTML=taskListHtml(visible);
  const all=$('#select-all-tasks');all.checked=visible.length>0&&visible.every(t=>selectedTasks.has(t.id));
  $('#visible-task-count').textContent=`표시된 ${visible.length}건 선택`;updateSelection();
}
function updateSelection(){
  const selected=[...state.tasks,...state.hiddenTasks].filter(t=>selectedTasks.has(t.id));
  const button=$('#bulk-hide');if(!button)return;
  $('#selection-count').textContent=selected.length+'건 선택';button.disabled=!selected.length;
  $('#bulk-group').disabled=!selected.length;
  const addable=selected.filter(t=>!t.hidden&&t.status!=='done'&&!planIds.includes(t.id));$('#bulk-today').disabled=!addable.length;$('#bulk-today').textContent=addable.length?`오늘 추가 (${addable.length})`:'오늘 추가';
  const local=selected.filter(t=>!t.jiraKey).length;
  button.textContent=filter==='hidden'?'선택 복원':local===0?'선택 숨기기':local===selected.length?'선택 삭제':'선택 삭제·숨기기';
}
async function removeTasks(tasks){
  const local=tasks.filter(t=>!t.jiraKey);
  if(local.length&&!window.confirm(`직접 등록한 할 일 ${local.length}건을 삭제할까요? 삭제한 할 일은 복원할 수 없습니다. 활동 기록과 워크로그는 남습니다.${tasks.length>local.length?' Jira 업무는 숨기기만 합니다.':''}`))return;
  const result=await api('/api/tasks/remove',{taskIds:tasks.map(t=>t.id)});
  selectedTasks.clear();await load();notice([result.deleted?`${result.deleted}건을 삭제했습니다.`:'',result.hidden?`${result.hidden}건을 숨겼습니다. Jira 상태는 바꾸지 않았습니다.`:''].filter(Boolean).join(' '));
}
function renderPlan() {
  const visible=filteredTasks(),projects=[...new Set([...state.tasks,...state.hiddenTasks].map(t=>t.jiraKey?t.jiraKey.split('-')[0]:'local'))].sort();
  $('#content').innerHTML=heading('전체 할 일','같은 목표의 업무를 모으고, 먼저 끝나야 하는 작업을 확인하세요',`<button class="button primary" data-open="task">할 일 등록</button>`)+`
  <div class="task-toolbar list-toolbar"><div class="list-filters">${[['open','남은 할 일'],['done','완료한 일'],['hidden','숨긴 일'],['all','전체']].map(([key,label])=>`<button class="filter ${filter===key?'active':''}" data-filter="${key}">${label}</button>`).join('')}</div><div class="task-filter-fields"><label>제품 <select id="product-filter" aria-label="제품"><option value="">모든 제품</option>${[...new Set(allTasks().map(t=>t.product||'미분류'))].sort().map(name=>`<option ${productFilter===name?'selected':''}>${esc(name)}</option>`).join('')}</select></label><label>성격 <select id="work-type-filter" aria-label="업무 성격"><option value="">모든 성격</option>${filterWorkTypes().map(name=>`<option ${workTypeFilter===name?'selected':''}>${esc(name)}</option>`).join('')}</select></label><label>프로젝트 <select id="project-filter" aria-label="프로젝트"><option value="">모든 프로젝트</option>${projects.map(p=>`<option value="${esc(p)}" ${projectFilter===p?'selected':''}>${p==='local'?'직접 등록':esc(p)}</option>`).join('')}</select></label><label>정렬 <select id="task-sort" aria-label="전체 할 일 정렬"><option value="priority" ${taskSort==='priority'?'selected':''}>중요도·긴급도순</option><option value="due" ${taskSort==='due'?'selected':''}>마감일순</option><option value="created-desc" ${taskSort==='created-desc'?'selected':''}>생성일 최신순</option><option value="created-asc" ${taskSort==='created-asc'?'selected':''}>생성일 오래된 순</option></select></label></div></div>
  <div class="sheet task-list-sheet"><div class="sheet-header bulk-toolbar"><label class="select-all"><input id="select-all-tasks" type="checkbox" ${visible.length&&visible.every(t=>selectedTasks.has(t.id))?'checked':''}> <span id="visible-task-count">표시된 ${visible.length}건 선택</span></label><div class="bulk-actions"><span id="selection-count">${selectedTasks.size}건 선택</span><button class="button" id="bulk-today" disabled>오늘 추가</button><button class="button" id="bulk-group" ${selectedTasks.size?'':'disabled'}>묶음·성격 변경</button><button class="button" id="bulk-hide" ${selectedTasks.size?'':'disabled'}>선택 숨기기</button><button class="text-button" id="clear-selection">선택 해제</button></div></div><div class="sheet-body"><label class="sr-only" for="task-search">업무 검색</label><input class="task-search" id="task-search" placeholder="이슈 번호, 업무 이름 또는 묶음 검색" value="${esc(taskSearch)}"><div id="all-task-list">${taskListHtml(visible)}</div></div></div>`;
  updateSelection();
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
const worklogStorageKey=day=>'work-agent.worklog-editor:'+day;
function worklogEdit(day){
  if(worklogEdits.has(day))return worklogEdits.get(day);
  try{const value=JSON.parse(localStorage.getItem(worklogStorageKey(day)));if(Array.isArray(value)){worklogEdits.set(day,value);return value;}}catch{}
  return null;
}
function worklogFormRows(){
  return [...document.querySelectorAll('#worklog-rows .worklog-input-row')].map(row=>({workId:row.dataset.workId||'',taskId:row.dataset.taskId||'',title:row.dataset.workTitle||'',jiraKey:$('.log-key',row).value.trim().toUpperCase(),duration:$('.log-duration',row).value,comment:$('.log-comment',row).value,marker:$('.log-marker',row).value,state:row.dataset.state||'draft'}));
}
function favoriteIssue(key){return (state.worklogFavorites||[]).some(row=>row.jiraKey===key);}
function worklogPicks(){
  const favorites=state.worklogFavorites||[],recent=(state.recentWorklogIssues||[]).filter(row=>!favoriteIssue(row.jiraKey));
  const choice=row=>`<div class="worklog-choice"><button type="button" class="worklog-choice-add" data-add-log-issue="${esc(row.jiraKey)}"><strong>${esc(row.jiraKey)}</strong><span>${esc(row.title||'이슈 제목 확인 전')}</span>${row.lastDate?`<small>최근 ${esc(row.lastDate)}</small>`:''}</button><button type="button" class="favorite-toggle" data-log-favorite="${esc(row.jiraKey)}" aria-label="${esc(row.jiraKey)} 즐겨찾기 ${favoriteIssue(row.jiraKey)?'해제':'추가'}" title="즐겨찾기 ${favoriteIssue(row.jiraKey)?'해제':'추가'}" aria-pressed="${favoriteIssue(row.jiraKey)}">${favoriteIssue(row.jiraKey)?'★':'☆'}</button></div>`;
  return `<section class="sheet worklog-picks"><div class="sheet-header"><h2>빠른 이슈 추가</h2></div><div class="sheet-body"><h3>즐겨찾기</h3><form id="worklog-favorite-form" class="quick-task"><label class="sr-only" for="favorite-issue-key">즐겨찾기 이슈 번호</label><input id="favorite-issue-key" name="jiraKey" placeholder="예: WOR-1517" required><button type="submit" class="button">추가</button></form>${favorites.map(choice).join('')||'<p class="hint">회의·지원 업무 이슈를 등록해 두세요</p>'}<details class="recent-log-issues" data-ledger-detail="recent-issues" ${openLedgerDetails.has('recent-issues')?'open':''}><summary>최근 기록한 이슈 <span>${recent.length}건</span></summary>${recent.map(choice).join('')||'<p class="hint">Jira 워크로그를 동기화하면 최근 이슈가 표시됩니다</p>'}</details></div></section>`;
}
function registeredWorklogHtml(){
  const rows=registeredOn(worklogDay),sync=state.sync.find(row=>row.id==='worklogs:'+monday(worklogDay));
  return `<section class="registered-worklogs"><div class="registered-heading"><h3>Jira에 등록된 기록</h3><strong>${durationText(registeredSeconds(worklogDay))||'0h'}</strong></div>${sync?.ok===false?'<p class="hint error">최근 동기화에 실패했습니다. 이전에 확인한 기록을 표시합니다.</p>':''}${rows.length?rows.map(row=>`<article class="registered-log"><div><h3>${issueLink(row.jiraKey,row.issueUrl)} <span>${esc(row.title)}</span></h3><small>${row.source==='work-agent'?'work-agent에서 등록':'Jira에서 등록'}${row.confirmed?'':' · 동기화 전'}</small></div><strong>${durationText(row.seconds)||'0h'}</strong></article>`).join(''):`<p class="hint">${sync?.ok?'이 날짜에 확인된 등록 기록이 없습니다':'Jira 워크로그를 동기화해 등록 시간을 확인하세요'}</p>`}<p class="hint">등록된 기록은 읽기 전용입니다. 변경·삭제는 Jira에서 수행한 뒤 다시 동기화하세요.</p></section>`;
}
function addWorklogIssue(key){
  const existing=[...document.querySelectorAll('#worklog-rows .worklog-input-row')].find(row=>$('.log-key',row).value.trim().toUpperCase()===key&&row.dataset.state!=='posted');
  if(existing){$('.log-duration',existing).focus();notice('이미 작성 중인 이슈입니다. 시간을 입력하세요.');return;}
  $('#worklog-rows').insertAdjacentHTML('beforeend',worklogRow({jiraKey:key,marker:'work-agent:'+crypto.randomUUID()}));rememberWorklog();updateTotal();$('#worklog-rows .worklog-input-row:last-child .log-duration').focus();
}
function rememberWorklog(){
  const rows=worklogFormRows();worklogEdits.set(worklogDay,rows);
  try{localStorage.setItem(worklogStorageKey(worklogDay),JSON.stringify(rows));}catch{}
  const label=$('#worklog-save-state');if(label)label.textContent='이 브라우저에 임시 보관';
  const day=document.querySelector(`[data-log-day="${worklogDay}"] span:last-child`);if(day){const registered=registeredSeconds(worklogDay);day.textContent='임시 보관'+(registered?' · '+durationText(registered):'');}
}
function clearWorklogEdit(day){worklogEdits.delete(day);try{localStorage.removeItem(worklogStorageKey(day));}catch{}}
function worklogCandidates(){return (state.worklogCandidates||[]).filter(row=>row.date===worklogDay);}
function newWorklogActivities(rows){
  const missing=worklogCandidates().filter(row=>!rows.some(item=>item.workId===row.workId||row.jiraKey&&item.jiraKey===row.jiraKey)&&!registeredOn(worklogDay).some(item=>row.jiraKey&&item.jiraKey===row.jiraKey));
  return missing.length?`<section class="sheet"><div class="sheet-header"><h2>추가로 확인한 활동</h2></div><div class="sheet-body"><p class="hint">저장한 초안은 유지합니다. 새로 확인한 업무를 추가하세요.</p>${missing.map(row=>`<div class="carryover-row"><span>${esc(row.title)}</span><button class="button" data-add-worklog-work="${esc(row.workId)}">시간 목록에 추가</button></div>`).join('')}</div></section>`:'';
}
function worklogEditorRows(draft,groups){
  const edits=worklogEdit(worklogDay),saved=draft?.items||[];
  if(edits===null)return draft?saved:worklogCandidates().filter(row=>!registeredOn(worklogDay).some(item=>row.jiraKey&&item.jiraKey===row.jiraKey)).filter((row,i,all)=>!row.jiraKey||all.findIndex(x=>x.jiraKey===row.jiraKey)===i).map(row=>({...row,seconds:0,comment:row.title,state:'draft'}));
  const locked=saved.filter(row=>['posted','posting','unknown'].includes(row.state));
  const same=(a,b)=>a.marker&&b.marker?a.marker===b.marker:a.jiraKey&&b.jiraKey?a.jiraKey===b.jiraKey:a.workId===b.workId;
  const candidates=worklogCandidates();
  const retained=edits.filter(row=>!row.workId||String(row.duration||'').trim()||row.seconds>0||saved.some(item=>same(row,item))||candidates.some(item=>item.workId===row.workId||row.jiraKey&&item.jiraKey===row.jiraKey));
  const rows=retained.map(row=>{const current=saved.find(item=>same(row,item));if(current&&locked.includes(current))return current;return {...row,...(current&&current.workId!==row.workId?{workId:current.workId,taskId:current.taskId,title:current.title,jiraKey:row.jiraKey||current.jiraKey}:{}),marker:current?.marker||row.marker,state:'draft'};});
  for(const row of locked)if(!rows.some(item=>same(item,row)))rows.push(row);
  return rows;
}
function renderWorklog() {
  const start=monday(state.date),days=Array.from({length:7},(_,i)=>dayAdd(start,i));
  const draft=state.worklogs.find(w=>w.date===worklogDay);
  const groups=state.activityGroups.filter(g=>g.date===worklogDay),rows=worklogEditorRows(draft,groups);
  $('#content').innerHTML=heading('워크로그','Jira에 등록한 시간을 확인하고, 빠진 업무를 채우세요',`<button class="button" id="sync-worklogs" ${syncingWorklogs||!settings.jiraConfigured?'disabled':''}>${syncingWorklogs?'Jira 확인 중…':'Jira 워크로그 동기화'}</button><button class="button" data-report="worklog">주간 자료 보기</button>`)+`
  <div class="worklog-week">${days.map(d=>{const saved=state.worklogs.find(w=>w.date===d),registered=registeredSeconds(d);return `<button class="day-button ${d===worklogDay?'active':''}" data-log-day="${d}"><span>${weekday(d)}</span><strong>${Number(d.slice(5,7))}.${Number(d.slice(8,10))}</strong><span>${worklogEdit(d)!==null?'임시 보관':saved?(saved.items.every(i=>i.state==='posted')?'등록 완료':'저장된 초안'):'미작성'}${registered?' · '+durationText(registered):''}</span></button>`;}).join('')}</div>
  <div class="allocation-layout"><section class="sheet"><div class="sheet-header"><h2>${shortDay(worklogDay)} 작업 시간</h2><span class="save-hint" id="worklog-save-state" role="status">${worklogEdit(worklogDay)!==null?'이 브라우저에 임시 보관':draft?'저장된 초안':'새 목록'}</span></div><div class="sheet-body"><form id="worklog-form"><div class="allocation-row worklog-input-row muted"><span>업무 · Jira 이슈</span><span>시간 · 미정 가능</span><span></span></div><div id="worklog-rows">${rows.map(row=>worklogRow(row)).join('')}</div><button type="button" class="text-button" id="add-worklog">+ 다른 이슈 추가</button><div class="allocation-total"><span>입력한 시간 합계</span><strong id="duration-total">0h</strong></div><p class="hint">입력 중인 내용은 이 브라우저에 임시 보관됩니다. 시간 없이 목록만 초안으로 저장한 뒤 나중에 채울 수 있습니다.</p><div class="form-actions"><button class="button" type="submit">초안 저장</button><button class="button primary" type="button" id="post-worklog" disabled>Jira에 등록</button></div></form>${!settings.jiraConfigured?'<p class="hint">Jira 연결 정보를 설정한 뒤 등록할 수 있습니다. 초안은 먼저 저장할 수 있습니다.</p>':''}</div></section>
  <div class="side-stack">${workReviewSheet(worklogDay,'worklog')}${newWorklogActivities(rows)}${worklogPicks()}<details class="sheet worklog-evidence" data-ledger-detail="worklog-evidence" ${openLedgerDetails.has('worklog-evidence')?'open':''}><summary class="review-summary"><span><strong>이날의 작업 근거</strong><small>${groups.length}개 업무 · 메시지 수와 세션 확인</small></span></summary><div class="sheet-body">${groupedActivities(groups)}<p class="hint">메시지 수는 실제 근무 시간으로 환산하지 않습니다.</p></div></details></div></div>`;
  $('#worklog-form').insertAdjacentHTML('afterend',registeredWorklogHtml());
  $('#duration-total').parentNode.firstElementChild.textContent='추가로 입력한 시간';
  $('#duration-total').parentNode.insertAdjacentHTML('afterend',`<div class="worklog-combined"><span>등록 ${durationText(registeredSeconds(worklogDay))||'0h'} + 추가 입력</span><strong id="combined-worklog-total"></strong></div>`);
  updateTotal();
  const week=monday(state.date),sync=state.sync.find(row=>row.id==='worklogs:'+week);
  if(!syncingWorklogs&&settings.jiraConfigured&&!attemptedWorklogWeeks.has(week)&&(!sync||!sync.ok||Date.now()-Date.parse(sync.at)>300000)){attemptedWorklogWeeks.add(week);queueMicrotask(()=>syncWorklogs());}
}
function worklogRow(row={}) {
  const locked=['posted','posting','unknown'].includes(row.state),errorId='worklog-time-error-'+(++worklogFieldId);
  return `<div class="allocation-row worklog-input-row" data-work-id="${esc(row.workId||'')}" data-task-id="${esc(row.taskId||'')}" data-work-title="${esc(row.title||'')}" data-state="${esc(row.state||'draft')}" ${row.state==='posted'?'hidden':''}><div>${row.title?`<strong class="worklog-task-title">${esc(row.title)}</strong>`:''}<input class="log-key" aria-label="Jira 이슈 번호" placeholder="${row.workId?'Jira 미연결 · 초안 저장 가능':'CT-123'}" value="${esc(row.jiraKey||'')}" ${row.workId?'':'required'} ${locked?'disabled':''}></div><div class="worklog-time"><input class="log-duration" aria-label="작업 시간" aria-describedby="${errorId}" aria-invalid="false" placeholder="예: 2h 30m" value="${esc(row.duration??durationText(row.seconds||0))}" ${locked?'disabled':''}><small class="duration-error" id="${errorId}" hidden>예: 4h, 2.5h, 2h 30m</small></div>${locked?'<span class="worklog-post-status">'+logStatus(row.state)+'</span>':'<button class="icon-button" type="button" data-remove-worklog aria-label="시간 기록 삭제">×</button>'}<input class="log-comment" type="hidden" value="${esc(row.comment??'업무 기록')}"><input class="log-marker" type="hidden" value="${esc(row.marker||(locked?'':('work-agent:'+crypto.randomUUID())))}"></div>`;
}
function updateTotal() {
  let total=0,invalid=false;
  document.querySelectorAll('#worklog-rows .worklog-input-row').forEach(row=>{const field=$('.log-duration',row),seconds=durationSeconds(field.value),bad=!!field.value.trim()&&(!Number.isInteger(seconds)||seconds>2147483647);field.setAttribute('aria-invalid',String(bad));$('.duration-error',row).hidden=!bad;if(bad)invalid=true;else if(row.dataset.state!=='posted'&&Number.isFinite(seconds))total+=seconds;});
  if($('#duration-total'))$('#duration-total').textContent=durationText(Math.round(total))||'0h';
  if($('#combined-worklog-total'))$('#combined-worklog-total').textContent=durationText(registeredSeconds(worklogDay)+Math.round(total))||'0h';
  const save=$('#worklog-form [type="submit"]');if(save)save.disabled=invalid||$('#worklog-form').dataset.saving==='true';
  const post=$('#post-worklog'),draft=state.worklogs.find(w=>w.date===worklogDay);
  if(post)post.disabled=invalid||worklogEdit(worklogDay)!==null||!draft||!settings.jiraConfigured||draft.items.every(i=>i.state==='posted')||draft.items.some(i=>i.state!=='posted'&&(i.seconds<=0||!i.jiraKey));
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
function syncTaskPicker(select){
  const input=document.getElementById(select.id+'-search');
  if(input)input.value=select.selectedOptions[0]?.textContent||'';
}
function taskPickers(root){
  root.querySelectorAll('select[data-task-picker]').forEach(select=>{
    const picker=document.createElement('div'),inputId=select.id+'-search',listId=select.id+'-options';
    picker.className='task-picker';
    picker.innerHTML=`<div class="task-picker-control"><input id="${inputId}" type="text" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" aria-expanded="false" aria-controls="${listId}" autocomplete="off" placeholder="이슈 번호 또는 작업 제목 검색"><button type="button" class="task-picker-toggle" aria-label="업무 목록 열기" tabindex="-1">⌄</button></div><div class="task-picker-popup" hidden><div id="${listId}" role="listbox"></div><p class="hint task-picker-empty" role="status" hidden>검색 결과가 없습니다. 검색어를 바꿔 보세요.</p></div>`;
    const labels=[...select.labels];select.hidden=true;select.classList.add('task-picker-source');select.after(picker);
    labels.forEach(label=>label.htmlFor=inputId);
    const input=$('input',picker),toggle=$('button',picker),popup=$('.task-picker-popup',picker),list=$('[role=listbox]',picker),empty=$('.task-picker-empty',picker);
    list.setAttribute('aria-label',labels.map(label=>label.textContent).join(' ')+' 검색 결과');
    const options=[...select.options].map((option,index)=>({value:option.value,title:option.textContent,index}));
    let choices=[],active=-1;
    function highlight(){
      [...list.children].forEach((node,index)=>{node.classList.toggle('active',index===active);});
      if(active>=0){input.setAttribute('aria-activedescendant',list.children[active].id);list.children[active].scrollIntoView({block:'nearest'});}else input.removeAttribute('aria-activedescendant');
    }
    function show(query=''){
      const words=query.toLocaleLowerCase().normalize('NFC').trim().split(/\s+/).filter(Boolean);
      choices=options.filter(option=>!option.value||words.every(word=>option.title.toLocaleLowerCase().normalize('NFC').includes(word)));
      list.innerHTML=choices.map(option=>`<button type="button" role="option" tabindex="-1" id="${listId}-${option.index}" data-choice="${option.index}" aria-selected="${option.value===select.value}">${esc(option.title)}</button>`).join('');
      empty.hidden=!words.length||choices.some(option=>option.value);
      active=words.length?choices.findIndex(option=>option.value):choices.findIndex(option=>option.value===select.value);
      popup.hidden=false;input.setAttribute('aria-expanded','true');highlight();
    }
    function close(){popup.hidden=true;input.setAttribute('aria-expanded','false');input.removeAttribute('aria-activedescendant');syncTaskPicker(select);}
    function choose(option){select.value=option.value;close();select.dispatchEvent(new Event('change',{bubbles:true}));}
    input.addEventListener('focus',()=>input.select());
    input.addEventListener('click',()=>{if(popup.hidden)show();});
    input.addEventListener('input',event=>{if(!event.isComposing)show(input.value);});
    input.addEventListener('compositionend',()=>show(input.value));
    input.addEventListener('blur',event=>{if(!event.relatedTarget||!$('#modal').contains(event.relatedTarget))close();});
    picker.addEventListener('task-picker-close',close);
    input.addEventListener('keydown',event=>{
      if(event.isComposing||event.keyCode===229)return;
      if(event.key==='Tab')close();
      if(event.key==='ArrowDown'||event.key==='ArrowUp'){
        event.preventDefault();if(popup.hidden){show();return;}
        if(choices.length)active=active<0?(event.key==='ArrowDown'?0:choices.length-1):(active+(event.key==='ArrowDown'?1:-1)+choices.length)%choices.length;highlight();
      }
      if(event.key==='Enter'&&!popup.hidden){event.preventDefault();event.stopPropagation();if(active>=0)choose(choices[active]);}
      if(event.key==='Escape'&&!popup.hidden){event.preventDefault();event.stopPropagation();close();}
    });
    toggle.addEventListener('pointerdown',event=>event.preventDefault());
    toggle.addEventListener('click',()=>{if(popup.hidden){input.focus();show();}else close();});
    list.addEventListener('pointerdown',event=>event.preventDefault());
    list.addEventListener('click',event=>{const option=event.target.closest('[data-choice]');if(option)choose(options[Number(option.dataset.choice)]);});
    select.addEventListener('change',()=>syncTaskPicker(select));syncTaskPicker(select);
  });
}
function modal(title,html) {
  $('#modal-title').textContent=title;$('#modal-content').innerHTML=html;taskPickers($('#modal-content'));
  if($('#new-linked-task'))$('#new-linked-task').hidden=!!$('#link-task').value;
  $('#modal').showModal();
}
function field(label,name,value='',type='text',required=false){return `<div class="field"><label for="field-${name}">${esc(label)}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" ${required?'required':''}></div>`;}
function openForm(kind,id) {
  let title='',html='';const task=id?[...state.tasks,...state.hiddenTasks].find(t=>t.id===id):null;
  if(kind==='task'){title=task?'업무 수정':'할 일 등록';html=field('업무 이름','title',task?.title||'','text',true)+structureFields(task)+`<div class="field-row">${field('제품','product',task?.product||'')}${field('Jira 이슈 번호','jiraKey',task?.jiraKey||'')}</div>`+field('마감일','due',task?.due||'','date')+`<div class="field"><label for="field-status">상태</label><select id="field-status" name="status" ${task?.jiraKey?'disabled':''}>${['todo','in-progress','done'].map(s=>`<option value="${s}" ${task?.status===s?'selected':''}>${statusName(s)}</option>`).join('')}</select></div><div class="checks"><label><input name="important" type="checkbox" ${task?.important?'checked':''}>중요한 일</label><label><input name="urgent" type="checkbox" ${task?.urgent?'checked':''}>급한 일</label></div>`;}
  if(kind==='activity'){title='활동 기록';html=field('수행한 일','title','','text',true)+field('날짜','date',view==='worklog'?worklogDay:state.date,'date',true)+`<div class="field"><label for="activity-task">업무에 연결 · 선택 사항</label><select id="activity-task" name="taskId" data-task-picker><option value="">별도 활동으로 기록</option>${state.tasks.map(task=>`<option value="${esc(task.id)}">${esc([task.jiraKey,task.title].filter(Boolean).join(' '))}</option>`).join('')}</select></div><div class="field-row">${field('Jira 이슈 번호','jiraKey')}${field('제품','product')}</div>`;}
  if(kind==='meeting'){title='회의 일정 추가';html=field('회의 이름','title','','text',true)+field('시작 시각 (한국 시간)','start',state.date+'T10:00','datetime-local',true)+field('종료 시각 (모르면 비워 두세요)','end','','datetime-local')+field('제품','product')+'<p class="hint">업무 에이전트 일정에 저장합니다. 외부 캘린더에는 등록하지 않습니다.</p>';}
  if(kind==='daily'){title='데일리 공유 내용';html=[['problems','발생한 문제'],['help','필요한 도움'],['decisions','결정이 필요한 사항'],['text','추가 공유']].map(([name,label])=>`<div class="field"><label for="daily-${name}">${label}</label><textarea id="daily-${name}" name="${name}" rows="2" placeholder="공유할 내용이 있을 때만 입력">${esc(name==='text'?state.dailyNote:state.dailyDetails?.[name]||'')}</textarea></div>`).join('')+'<p class="hint">진행한 일은 실제 활동과 확인한 수행 상태에서 가져옵니다. 계획만 한 일은 포함하지 않습니다. 빈 항목은 브리핑에 표시하지 않습니다.</p>';}
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
    if(b.dataset.reviewRepository){delete $('#review-repository').dataset.localPathDirty;delete $('#review-repository').dataset.sourcesDirty;const row=(reviewState.repositories||[]).find(row=>row.repository===b.dataset.reviewRepository)||{repository:b.dataset.reviewRepository};$('#review-repository-name').value=row.repository;$('#review-repository-sources').value=(row.sources||[]).join('\n');$('#review-local-path').value=(reviewState.localRepositories||[]).find(local=>local.repository===row.repository)?.localPath||'';}
    if(b.id==='refresh-reviews'){b.disabled=true;try{await api('/api/reviews/refresh',{});await updateReviews();}finally{b.disabled=false;}}
    if(b.dataset.bindPrSession){const selected=(reviewState.sessionBindings||[]).find(row=>row.url===b.dataset.bindPrSession)?.sessionId;modal('원래 작업 세션 연결',`<form id="pr-session-form" data-url="${esc(b.dataset.bindPrSession)}"><p class="hint">원본 대화는 유지하고 답변용 대화를 분기합니다. 다음 댓글 답변이나 후속 질문부터 적용합니다.</p><label for="pr-session-choice">작업 세션</label><select id="pr-session-choice" name="sessionId"><option value="">자동 검색</option>${(reviewState.sessions||[]).map(row=>`<option value="${esc(row.id)}" ${selected===row.id?'selected':''}>${esc(row.name)} · ${esc(row.cwd)}</option>`).join('')}</select><div class="form-actions"><button class="button primary" type="submit">연결</button></div></form>`);}
    if(b.dataset.saveReview){b.disabled=true;await api('/api/reviews/save',{id:b.dataset.saveReview});await updateReviews();notice('댓글 저장 상태를 확인했습니다. Bitbucket에서 비공개 초안을 확인하세요.');}
    if(b.dataset.retryReview){const job=reviewState.jobs.find(row=>row.id===b.dataset.retryReview);b.disabled=true;await api('/api/reviews/request',{url:job.url,commentId:job.parentCommentId||0,referenceUrls:job.referenceUrls||[],rerun:true,validation:!!job.validation});await updateReviews();}
    if(b.dataset.editReview){const job=reviewState.jobs.find(row=>row.id===b.dataset.editReview),item=job.kind==='reply'?{comment:job.reply}:job.findings.find(row=>row.id===b.dataset.findingId);modal('PR 댓글 초안 편집',`<form id="review-edit" data-id="${esc(job.id)}" data-finding-id="${esc(b.dataset.findingId)}"><div class="field"><label for="review-text">댓글 내용</label><textarea id="review-text" name="text" rows="9" required>${esc(item.comment)}</textarea></div><div class="form-actions"><button class="button primary" type="submit">초안 저장</button></div></form>`);}
    if(b.dataset.addWorklogWork){const row=worklogCandidates().find(row=>row.workId===b.dataset.addWorklogWork);$('#worklog-rows').insertAdjacentHTML('beforeend',worklogRow({...row,comment:row.title}));b.disabled=true;rememberWorklog();updateTotal();}
    if(b.dataset.linkWork){const row=workDayRows(b.dataset.date).find(row=>row.workId===b.dataset.linkWork);modal('활동과 업무 연결',`<form id="work-link-form" data-date="${esc(b.dataset.date)}" data-work-id="${esc(row.workId)}"><div class="link-source"><span>연결할 활동</span><p>${esc(row.title)}</p></div><div class="field"><label for="link-task">연결할 업무</label><select name="taskId" id="link-task" data-task-picker><option value="">새 업무로 등록 · 활동 제목 사용</option>${state.tasks.map(task=>`<option value="${esc(task.id)}" ${task.id===row.suggestedTaskId?'selected':''}>${esc([task.jiraKey,task.title].filter(Boolean).join(' '))}</option>`).join('')}</select></div><div class="field" id="new-linked-task"><label for="link-jira">새 업무의 Jira 이슈 · 선택 사항</label><input id="link-jira" name="jiraKey" placeholder="Jira가 없으면 비워 두세요"></div><div class="form-actions"><button class="button primary" type="submit">연결</button></div></form>`);}
    if(b.dataset.reviewDay){reviewDate=b.dataset.reviewDay;await fetchWorkDay(reviewDate);renderToday();}
    if(b.id==='add-previous-work'){b.disabled=true;try{await openPastWork(b.dataset.addActualDate);}finally{b.disabled=false;}}
    if(b.dataset.choosePastWork){const row=pastWorkCandidates.find(row=>row.workId===b.dataset.choosePastWork),form=$('#past-work-form');form.dataset.workId=row.workId;form.elements.taskId.value=row.taskId||'';syncTaskPicker(form.elements.taskId);form.elements.title.value=row.title;form.elements.title.readOnly=true;form.elements.outcome.value=findTask(row.taskId)?.status==='done'?'done':'progress';form.elements.outcome.focus();}
    if(b.id==='reset-past-work'){const form=$('#past-work-form');delete form.dataset.workId;form.elements.taskId.value='';syncTaskPicker(form.elements.taskId);form.elements.title.value='';form.elements.title.readOnly=false;form.elements.title.focus();}
    if(b.dataset.open){openForm(b.dataset.open);if(b.dataset.forToday)$('#record-form').dataset.forToday='true';if(b.dataset.activityDate)$('#record-form').elements.date.value=b.dataset.activityDate;}
    if(b.dataset.editTask)openForm('task',b.dataset.editTask);
    if(b.dataset.openStatus){b.disabled=true;try{await openStatus(findTask(b.dataset.openStatus));}finally{b.disabled=false;}}
    if(b.id==='bulk-today'){b.disabled=true;try{const tasks=allTasks().filter(t=>selectedTasks.has(t.id)&&!t.hidden&&t.status!=='done'&&!planIds.includes(t.id));const result=await api('/api/plan/items',{date:state.date,taskIds:tasks.map(t=>t.id)});selectedTasks.clear();await load();notice(`${result.added}건을 오늘 할 일에 추가했습니다.`);}finally{if(b.isConnected)updateSelection();}}
    if(b.id==='bulk-group'){const tasks=allTasks().filter(t=>selectedTasks.has(t.id)),names=new Set(tasks.map(workGroup)),initial=names.size===1?[...names][0]:'';modal('업무 분류 일괄 변경',`<form id="group-form"><p class="hint">선택한 ${tasks.length}건에 지정한 항목만 반영합니다. 입력하지 않은 분류는 유지합니다.</p>${groupField(initial)}<button type="button" class="text-button" id="clear-bulk-group">선택한 업무의 묶음 해제</button><div class="field bulk-work-type"><label for="bulk-work-type">업무 성격</label><select id="bulk-work-type" name="workType"><option value="__keep__">변경하지 않음</option><option value="">미분류로 변경</option>${workTypes().map(type=>`<option>${esc(type)}</option>`).join('')}</select></div><div class="form-actions"><button class="button primary" type="submit">저장</button></div></form>`);$('#group-form').dataset.taskIds=JSON.stringify(tasks.map(t=>t.id));$('#group-form').dataset.initialGroup=initial;}
    if(b.id==='clear-bulk-group'){$('#group-form').elements.workGroup.value='';$('#group-form').dataset.clearGroup='true';}
    if(b.dataset.filter){filter=b.dataset.filter;selectedTasks.clear();renderPlan();}
    if(b.dataset.todayStatus){todayStatus=b.dataset.todayStatus;renderToday();}
    if(b.id==='clear-today')await saveTodayOrder([],state.planSort);
    if(b.dataset.todayAdd||b.dataset.todayRemove||b.dataset.todayToggle){const id=b.dataset.todayAdd||b.dataset.todayRemove||b.dataset.todayToggle;const included=b.dataset.todayAdd?true:b.dataset.todayRemove?false:!planIds.includes(id);b.disabled=true;await api('/api/plan/item',{date:state.date,id,included});await load();notice(included?'오늘 할 일에 추가했습니다.':'오늘 할 일에서 제외했습니다. 활동과 워크로그는 보존했습니다.');}
    if(b.id==='clear-selection'){selectedTasks.clear();renderPlan();}
    if(b.id==='bulk-hide'){b.disabled=true;try{if(filter==='hidden'){const count=selectedTasks.size;await api('/api/tasks/hide-many',{taskIds:[...selectedTasks],hidden:false});selectedTasks.clear();await load();notice(`${count}건을 복원했습니다.`);}else await removeTasks([...state.tasks,...state.hiddenTasks].filter(t=>selectedTasks.has(t.id)));}finally{if(b.isConnected)updateSelection();}}
    if(b.id==='refresh-draft-results'){b.disabled=true;b.textContent='정리 중…';try{await api('/api/draft-results/refresh?date='+state.date,{});await load();notice('Draft PR 작업 결과를 정리했습니다.');}finally{if(b.isConnected){b.disabled=false;b.textContent='다시 정리';}}}
    if(b.dataset.draftReviewed){b.disabled=true;await api('/api/draft-results/reviewed',{id:b.dataset.draftReviewed,reviewed:b.dataset.reviewed==='true'});await load();}
    if(b.dataset.dismissFollowup){b.disabled=true;await api('/api/followups/dismiss',{id:b.dataset.dismissFollowup});await load();}
    if(b.dataset.acceptFollowup){b.disabled=true;await api('/api/followups/accept',{id:b.dataset.acceptFollowup,date:state.date});await load();notice('선택한 작업을 오늘 할 일에 추가했습니다.');}
    if(b.dataset.hideTask){const task=[...state.tasks,...state.hiddenTasks].find(t=>t.id===b.dataset.hideTask);if(task.jiraKey&&task.hidden){await api('/api/tasks/hide',{id:task.id,hidden:false});await load();notice('목록에 다시 표시했습니다.');}else await removeTasks([task]);}
    if(b.id==='ask-team-statistics'){const member=team.members.find(m=>m.id===selectedMember);$('#chat-body').hidden=false;$('#toggle-chat').textContent='대화 접기';$('#toggle-chat').setAttribute('aria-expanded','true');$('#chat-text').value=`${settings.agentCallName}, ${member.name}이 이번 하반기에 담당한 이슈 수와 등록 시간을 알려줘.`;$('#chat-text').focus();}
    if(b.dataset.report)await showReport(b.dataset.report);
    if(b.dataset.evidence){const e=await api('/api/evidence?id='+encodeURIComponent(b.dataset.evidence));modal('작업 근거',`<p class="hint">${esc(e.sourceRef||'')}</p><pre>${esc(e.text)}</pre>`);}
    if(b.dataset.logDay){worklogDay=b.dataset.logDay;renderWorklog();}
    if(b.id==='sync-worklogs')await syncWorklogs(true);
    if(b.dataset.addLogIssue)addWorklogIssue(b.dataset.addLogIssue);
    if(b.dataset.logFavorite){b.disabled=true;await api('/api/worklogs/favorite',{jiraKey:b.dataset.logFavorite,favorite:!favoriteIssue(b.dataset.logFavorite)});await load();}
    if(b.id==='add-worklog'){$('#worklog-rows').insertAdjacentHTML('beforeend',worklogRow());rememberWorklog();updateTotal();}
    if(b.hasAttribute('data-remove-worklog')){b.closest('.worklog-input-row').remove();rememberWorklog();updateTotal();}
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
    if(el.dataset.taskStatus&&el.value){const body={id:el.dataset.taskStatus};if(el.dataset.remote==='true')body.transitionId=el.value;else body.status=el.value;el.disabled=true;await api('/api/tasks/status',body);$('#modal').close();await load();notice('업무 상태를 변경했습니다.');}
    if(el.id==='link-task')$('#new-linked-task').hidden=!!el.value;
    if(el.id==='past-work-task'){const form=$('#past-work-form'),task=findTask(el.value);if(task){form.elements.title.value=task.title;form.elements.title.readOnly=true;form.elements.outcome.value=task.status==='done'?'done':'progress';}else{delete form.dataset.workId;form.elements.title.value='';form.elements.title.readOnly=false;}}
    if(el.dataset.workOutcome){el.disabled=true;await api('/api/work-days',{date:el.dataset.date,workId:el.dataset.workOutcome,outcome:el.value});await load();}
    if(el.id==='work-review-date'&&el.value){reviewDate=el.value;await fetchWorkDay(reviewDate);renderToday();}
    if(el.id==='group-today'){groupToday=el.checked;renderToday();}
    if(el.id==='selected-date'){worklogDay='';await load();}
    if(el.id==='today-sort')await saveTodayOrder(planIds,el.value);
    if(el.id==='project-filter'){projectFilter=el.value;selectedTasks.clear();renderPlan();}
    if(el.id==='product-filter'){productFilter=el.value;selectedTasks.clear();renderPlan();}
    if(el.id==='work-type-filter'){workTypeFilter=el.value;selectedTasks.clear();renderPlan();}
    if(el.id==='task-sort'){taskSort=el.value;renderPlan();}
    if(el.dataset.selectTask){el.checked?selectedTasks.add(el.dataset.selectTask):selectedTasks.delete(el.dataset.selectTask);updateSelection();$('#select-all-tasks').checked=filteredTasks().every(t=>selectedTasks.has(t.id));}
    if(el.id==='select-all-tasks'){for(const task of filteredTasks())el.checked?selectedTasks.add(task.id):selectedTasks.delete(task.id);renderPlan();}
  }catch(e){notice(e.message);await load();}
});
document.addEventListener('input',event=>{const el=event.target;if(el.closest('#worklog-form')){rememberWorklog();updateTotal();}if(el.id==='review-local-path')$('#review-repository').dataset.localPathDirty='true';if(el.id==='review-repository-sources')$('#review-repository').dataset.sourcesDirty='true';if(el.id==='task-search'){taskSearch=el.value;if(!event.isComposing)updateTaskList();}if(el.id==='past-work-search'){document.querySelectorAll('[data-past-work-option]').forEach(row=>{row.hidden=!row.textContent.toLowerCase().includes(el.value.toLowerCase());});}if(el.id==='predecessor-search'){document.querySelectorAll('[data-predecessor-option]').forEach(label=>{label.hidden=!label.textContent.toLowerCase().includes(el.value.toLowerCase());});}});
document.addEventListener('compositionend',event=>{if(event.target.id==='task-search'){taskSearch=event.target.value;updateTaskList();}});
document.addEventListener('pointerdown',event=>{document.querySelectorAll('.task-menu[open]').forEach(menu=>{if(!menu.contains(event.target))menu.open=false;});});
document.addEventListener('keydown',event=>{if(event.key==='Escape')document.querySelectorAll('.task-menu[open]').forEach(menu=>{menu.open=false;$('summary',menu).focus();});});
document.addEventListener('pointerdown',event=>{const handle=event.target.closest('[data-drag-task]');if(!handle||savingPlan||event.button!==0)return;event.preventDefault();draggedId=handle.dataset.dragTask;dragOriginY=event.clientY;didDrag=false;handle.setPointerCapture(event.pointerId);});
document.addEventListener('pointermove',event=>{if(!draggedId||Math.abs(event.clientY-dragOriginY)<5&&!didDrag)return;didDrag=true;const dragged=[...document.querySelectorAll('.today-task')].find(t=>t.dataset.todayId===draggedId);if(!dragged)return;dragged.classList.add('dragging');const target=document.elementFromPoint(event.clientX,event.clientY)?.closest('.today-task');if(target&&target!==dragged&&target.closest('#today-list')&&target.parentNode===dragged.parentNode){const rect=target.getBoundingClientRect();target.parentNode.insertBefore(dragged,event.clientY<rect.top+rect.height/2?target:target.nextSibling);}if(event.clientY>innerHeight-40)window.scrollBy(0,18);else if(event.clientY<40)window.scrollBy(0,-18);});
document.addEventListener('pointerup',async()=>{if(!draggedId)return;const ids=[...document.querySelectorAll('#today-list [data-today-id]')].map(t=>t.dataset.todayId);draggedId='';document.querySelectorAll('.dragging').forEach(t=>t.classList.remove('dragging'));if(didDrag)await saveTodayOrder([...ids,...planIds.filter(id=>!ids.includes(id))]);didDrag=false;});
document.addEventListener('pointercancel',()=>{if(draggedId){draggedId='';didDrag=false;render();}});
document.addEventListener('keydown',async event=>{const handle=event.target.closest('[data-drag-task]');if(!handle||!event.altKey||!['ArrowUp','ArrowDown'].includes(event.key))return;event.preventDefault();const row=handle.closest('.today-task'),siblings=[...row.parentNode.children].filter(el=>el.matches('.today-task')),from=siblings.indexOf(row),to=from+(event.key==='ArrowUp'?-1:1);if(to<0||to>=siblings.length)return;row.parentNode.insertBefore(row,to<from?siblings[to]:siblings[to].nextSibling);const ids=[...document.querySelectorAll('#today-list [data-today-id]')].map(el=>el.dataset.todayId);await saveTodayOrder([...ids,...planIds.filter(id=>!ids.includes(id))]);});
document.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target;const submit=$('[type="submit"]',form);if(submit)submit.disabled=true;
  try {
    if(form.id==='review-repository'){const values=new FormData(form),repository=form.elements.repository.value.trim();if(!form.dataset.localPathDirty&&!form.dataset.sourcesDirty)throw new Error('변경할 저장소 경로나 외부 명세를 입력하세요.');if(form.dataset.localPathDirty)await api('/api/reviews/workspace',{repository,localPath:form.elements.localPath.value.trim()});if(form.dataset.sourcesDirty)await api('/api/reviews/repositories',{repository,sources:String(values.get('sources')||'').split(/\r?\n/).map(value=>value.trim()).filter(Boolean)});delete form.dataset.localPathDirty;delete form.dataset.sourcesDirty;await updateReviews();notice('변경한 연결 설정만 저장했습니다. 다음 검토부터 적용합니다.');}
    if(form.id==='review-request'){await api('/api/reviews/request',{url:new FormData(form).get('url'),referenceUrls:String(new FormData(form).get('references')||'').split(/\r?\n/).map(url=>url.trim()).filter(Boolean)});await updateReviews();notice('PR 검토를 요청했습니다. 결과가 나오면 목록에 표시합니다.');}
    if(form.id==='pr-session-form'){await api('/api/reviews/session',{url:form.dataset.url,sessionId:form.elements.sessionId.value});$('#modal').close();await updateReviews();notice('PR 작업 세션 연결을 저장했습니다.');}
    if(form.id==='review-edit'){await api('/api/reviews/edit',{id:form.dataset.id,findingId:form.dataset.findingId,text:new FormData(form).get('text')});$('#modal').close();await updateReviews();notice('초안을 저장했습니다.');}
    if(form.id==='quick-task-form'){await api('/api/tasks',{title:$('#quick-task-title').value.trim(),today:true,date:state.date});await load();notice('오늘 할 일에 추가했습니다.');}
    if(form.id==='worklog-favorite-form'){await api('/api/worklogs/favorite',{jiraKey:form.elements.jiraKey.value.trim().toUpperCase(),favorite:true});await load();notice('워크로그 즐겨찾기에 추가했습니다.');}
    if(form.id==='group-form'){const data={taskIds:JSON.parse(form.dataset.taskIds)},group=form.elements.workGroup.value.trim(),type=form.elements.workType.value;if(group!==form.dataset.initialGroup||form.dataset.clearGroup==='true')data.workGroup=group;if(type!=='__keep__')data.workType=type;if(!Object.hasOwn(data,'workGroup')&&!Object.hasOwn(data,'workType'))throw new Error('변경할 묶음이나 업무 성격을 지정하세요.');await api('/api/tasks/group',data);$('#modal').close();await load();notice('선택한 업무의 분류를 변경했습니다.');}
    if(form.id==='past-work-form'){await api('/api/work-days/add',{date:form.dataset.date,workId:form.dataset.workId||'',taskId:form.elements.taskId.value,title:form.elements.title.value.trim(),outcome:form.elements.outcome.value});$('#modal').close();await load();notice('선택한 날짜의 수행 내용에 추가했습니다.');}
    if(form.id==='work-link-form'){const taskId=form.elements.taskId.value;await api('/api/work-links',{date:form.dataset.date,workId:form.dataset.workId,taskId,jiraKey:form.elements.jiraKey.value,create:!taskId});$('#modal').close();await load();notice('활동을 업무에 연결했습니다.');}
    if(form.id==='record-form'){
      const kind=form.dataset.kind,data=Object.fromEntries(new FormData(form));
      if(form.dataset.id)data.id=form.dataset.id;
      if(kind==='task'){const task=findTask(form.dataset.id),ids=new FormData(form).getAll('predecessorIds');data.workGroup=data.workGroup.trim();if(data.workGroup===(task?workGroup(task):''))delete data.workGroup;if(JSON.stringify([...ids].sort())!==JSON.stringify((task?predecessors(task).map(t=>t.id):[]).sort()))data.predecessorIds=ids;else delete data.predecessorIds;data.important=form.elements.important.checked;data.urgent=form.elements.urgent.checked;if(!data.product)data.product='미분류';if(form.dataset.forToday){data.today=true;data.date=state.date;}}
      if(kind==='meeting'){data.start+=':00+09:00';if(data.end)data.end+=':00+09:00';}
      if(kind==='daily')data.date=state.date;
      if(kind==='observation')data.memberId=selectedMember;
      await api('/api/'+({task:'tasks',activity:'activities',meeting:'meetings',daily:'daily',member:'members',observation:'observations'}[kind]),data);
      $('#modal').close();await load();notice('저장했습니다.');
    }
    if(form.id==='worklog-form'){
      form.dataset.saving='true';
      updateTotal();if(document.querySelector('.log-duration[aria-invalid="true"]'))return;
      const date=worklogDay,rows=worklogFormRows(),snapshot=JSON.stringify(worklogEdit(date));
      await api('/api/worklogs',{date,items:rows.map(({state,...item})=>item)});
      if(JSON.stringify(worklogEdit(date))===snapshot)clearWorklogEdit(date);
      await load();notice('워크로그 초안을 저장했습니다. Jira 등록은 별도로 실행하세요.');
    }
    if(form.id==='chat-form'){
      const text=$('#chat-text').value.trim();if(!text)return;appendMessage('user',text);$('#chat-text').value='';
      const result=await api('/api/chat',{text,requestId:crypto.randomUUID()});appendMessage('assistant',result.text);await load();
    }
  }catch(e){notice(e.message);if(form.id==='chat-form')appendMessage('assistant',e.message);}
  finally{delete form.dataset.saving;if(submit)submit.disabled=false;if(form.id==='worklog-form'&&$('#worklog-form'))updateTotal();}
});
function appendMessage(role,text){const node=document.createElement('div');node.className='message '+role;const label=document.createElement('strong');label.textContent=role==='user'?'나':settings?.agentName||'work-agent';node.append(label,document.createTextNode(text));$('#messages').append(node);$('#messages').scrollTop=$('#messages').scrollHeight;}
$('#toggle-chat').addEventListener('click',async()=>{const body=$('#chat-body');body.hidden=!body.hidden;$('#toggle-chat').textContent=body.hidden?'대화 열기':'대화 접기';$('#toggle-chat').setAttribute('aria-expanded',!body.hidden);if(!body.hidden&&!$('#messages').children.length){try{const messages=await api('/api/messages');messages.slice(-20).forEach(m=>appendMessage(m.role,m.text));}catch(e){notice(e.message);}}});
$('#modal').addEventListener('click',event=>{document.querySelectorAll('.task-picker').forEach(picker=>{if(!picker.contains(event.target))picker.dispatchEvent(new Event('task-picker-close'));});});
$('#close-modal').addEventListener('click',()=>$('#modal').close());
$('#refresh').addEventListener('click',async()=>{const b=$('#refresh');b.disabled=true;b.textContent='확인 중…';try{const result=await api('/api/sync',{kind:'all'});await load();const failed=state.sync.filter(s=>s.ok===false);notice(failed.length?'일부 연결을 확인하지 못했습니다. 연결 상태를 확인하세요.':'새 기록을 확인했습니다.');}catch(e){notice(e.message);}finally{b.disabled=false;b.textContent='기록 새로 확인';}});
$('#connections').addEventListener('click',()=>modal('연결 상태',`<div class="connection-row"><div><strong>Slack</strong><small>SLACK_APP_TOKEN · SLACK_BOT_TOKEN · SLACK_USER_ID</small></div><span class="${settings.slackConnected?'':'muted'}">${settings.slackConnected?'연결됨':settings.slackConfigured?'연결 확인 중':'설정 필요'}</span></div><div class="connection-row"><div><strong>Jira</strong><small>JIRA_URL · JIRA_EMAIL · JIRA_TOKEN_ENV에 지정한 환경변수</small></div><span>${settings.jiraConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>캘린더</strong><small>CALENDAR_ICS_URL 또는 CALENDAR_ICS_FILE</small></div><span>${settings.calendarConfigured?'설정됨':'설정 필요'}</span></div><div class="connection-row"><div><strong>자연어 처리</strong><small>로그인한 ${esc(settings.engine)} CLI를 사용합니다.</small></div><span>${esc(settings.engine)}</span></div><div class="connection-row"><div><strong>정기 브리핑</strong><small>평일 ${esc(settings.briefingTime||'꺼짐')} · 금요일 워크로그 ${esc(settings.fridayTime||'꺼짐')}<br>Asia/Seoul · 프로그램이 실행 중일 때 동작합니다.</small></div></div>${[['Claude',settings.claudeSessionTime,settings.claudeSession],['Codex',settings.codexSessionTime,settings.codexSession]].map(([name,time,session])=>`<div class="connection-row"><div><strong>${name} 예약 요청</strong><small>매일 한국 시간 ${esc(time||'꺼짐')} · 주말 포함<br>최근 결과: ${esc(({complete:'완료',failed:'실패',running:'결과 확인 필요'})[session?.status]||'실행 전')}</small></div></div>`).join('')}<p class="hint">프로젝트의 config.properties에 값을 입력한 뒤 프로그램을 다시 시작하세요. 자세한 절차는 README.md에 있습니다.</p>${settings.slackError?`<p class="hint error">${esc(settings.slackError)}</p>`:''}`));
load().catch(e=>{$('#content').innerHTML=empty('업무 기록을 불러오지 못했습니다.',e.message);});
setInterval(async()=>{
  if(!state||document.hidden||savingPlan||draggedId||$('#modal').open||document.querySelector('.task-menu[open]')||!['today','plan'].includes(view)||document.activeElement.matches('input,textarea,select'))return;
  try{const next=await api('/api/state?date='+state.date);if(JSON.stringify([next.tasks,next.hiddenTasks,next.planVersion,next.jobs,next.followups,next.todayGroups,next.draftDigest,next.todayWork,next.previousWork,next.carryover])!==JSON.stringify([state.tasks,state.hiddenTasks,state.planVersion,state.jobs,state.followups,state.todayGroups,state.draftDigest,state.todayWork,state.previousWork,state.carryover])){state=next;planIds=state.plan.map(t=>t.id);render();}}catch{}
},30000);

document.addEventListener('toggle',event=>{const group=event.target;if(group.matches('[data-ledger-detail]')){group.open?openLedgerDetails.add(group.dataset.ledgerDetail):openLedgerDetails.delete(group.dataset.ledgerDetail);if(group.open&&group.matches('[data-review-date]')&&group.dataset.reviewDate!==state.date&&group.dataset.reviewDate!==state.previousWorkDate&&!workDayCache.has(group.dataset.reviewDate)){fetchWorkDay(group.dataset.reviewDate).then(()=>{if(view==='worklog')renderWorklog();else if(view==='today')renderToday();}).catch(e=>notice(e.message));}}if(group.matches('[data-candidate-group]')){group.open?expandedCandidates.add(group.dataset.candidateGroup):expandedCandidates.delete(group.dataset.candidateGroup);}if(group.matches('[data-work-group]')){group.open?collapsedWorkGroups.delete(group.dataset.workGroup):collapsedWorkGroups.add(group.dataset.workGroup);}if(group.matches('.task-menu[open]'))document.querySelectorAll('.task-menu[open]').forEach(other=>{if(other!==group)other.open=false;});},true);
