/* Offline interaction studies. No network, storage, permissions, or real reminders. */
'use strict';
const variant = document.body.dataset.variant;
const app = document.querySelector('#app');
const state = { delay:3, interval:5, repeat:true, title:'点外卖', utterance:'待会儿记得提醒我点外卖', timeOpen:false, detailsOpen:false, composeOpen:false, message:'', error:'', pose:'speak', phase:'proposal', permission:true, complex:false, endAt:'', busy:false };
let replyTimer, toastTimer, manualPaused=false;
const esc = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const arrow = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg>';
const mascot = (pose=state.pose) => `<span class="mascot" data-pose="${pose}" role="img" aria-label="念念种子，${{idle:'倾听',think:'思考',speak:'说话',happy:'开心'}[pose]}"></span>`;
function clockLabel(date, full=false) { return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',...(full?{year:'numeric',month:'2-digit',day:'2-digit'}:{}),hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(date); }
function firstTime(){ return new Date(Date.now()+state.delay*60000); }
function timing(){return state.complex?'工作日 8:30':`${state.delay} 分钟后`;}
function followup(){return state.complex?'每个工作日提醒一次，<br>当天做完就停，第二天继续。':state.repeat?`还没完成，就每 <strong>${state.interval} 分钟</strong>提醒你。<br>做完就停${state.endAt?'，最晚到 '+esc(state.endAt.replace('T',' ')):''}。`:'只提醒这一次，<br>之后不再催你。';}
function detailMarkup(){return `<details class="details" ${state.detailsOpen?'open':''}><summary>${state.permission?'通知提醒':'通知还未开启'} · 查看细节</summary><div class="detail-content"><p>${state.complex?'示例规则：工作日 08:30，按天分别完成。':`首次提醒：${clockLabel(firstTime(),true)}（预计）<br>以确认时刻起算 ${state.delay} 分钟。`}<br>时区：Asia/Shanghai</p><label>这件小事<input id="task-title" maxlength="60" value="${esc(state.title)}"></label>${state.complex?'':`<label>没完成时<select id="repeat-rule"><option value="0" ${!state.repeat?'selected':''}>只提醒一次</option>${[...new Set([2,5,10,15,state.interval])].sort((a,b)=>a-b).map(n=>`<option value="${n}" ${state.repeat&&state.interval===n?'selected':''}>每 ${n} 分钟继续提醒</option>`).join('')}</select></label><label>最晚提醒到（可不填）<input id="end-at" type="datetime-local" step="1" value="${esc(state.endAt)}"></label>`}<p>本例使用手机通知。实际 App 应根据已开通的渠道展示；缺少权限时引导设置，保留当前草稿。</p><p>做完小事再领取水滴；确认安排不会完成任务，也不会让花长大。</p><div class="helper">${state.complex?'理由：工作日的事按天安排，避免周末打扰。':'为什么这样安排：你说“待会儿”，先建议 3 分钟后；催办默认 5 分钟，可直接修改。'}</div></div></details>`;}
function proposalMarkup(){return `<div class="proposal" aria-live="polite"><h1 class="main-sentence">我会在 <button class="inline-time" data-action="time" aria-expanded="${state.timeOpen}" aria-controls="time-options">${timing()}${arrow}</button><br>提醒你${esc(state.title)}。</h1><p class="follow-up">${followup()}</p></div><div class="time-options" id="time-options" ${state.timeOpen?'':'hidden'}><span class="small-label">${state.complex?'切回一次小事的示例':'从确认那一刻开始算'}</span><div class="choices">${[3,5,10,30].map(n=>`<button class="chip" data-delay="${n}" aria-pressed="${!state.complex&&state.delay===n}">${n} 分钟后</button>`).join('')}</div></div>${!state.permission?'<div class="notice">还差一步：开启通知，念念才能提醒你。<br><button data-action="permission">模拟开启通知，保留这份安排</button></div>':''}${detailMarkup()}`;}
function composerMarkup(){return `<section class="composer" ${state.composeOpen?'':'hidden'}><label for="revision">想怎么改，直接说</label><textarea id="revision" maxlength="160" placeholder="例如：改成10分钟后">${esc(state.message)}</textarea><div class="choices"><button class="chip" data-message="改成10分钟后">改成10分钟后</button><button class="chip" data-message="只提醒一次">只提醒一次</button><button class="chip" data-message="每2分钟提醒">每2分钟提醒</button><button class="chip" data-message="提醒我收衣服">改成收衣服</button></div><p class="composer-error" role="status">${esc(state.error)}</p><button class="submit" data-action="revise" ${state.busy?'disabled':''}>${state.busy?'念念想一想…':'更新这份安排'}</button><p class="footnote">样稿支持以上四种说法，未连接 AI。</p></section>`;}
function mainMarkup(){
 if(state.phase==='saved')return `<div class="success">${mascot('happy')}<h1>好，说定啦。</h1><p><span class="saved-time">${state.complex?'下一个工作日 08:30':clockLabel(state.confirmedAt)}</span>提醒你${esc(state.title)}。</p><p style="margin-top:24px">这是确认反馈的演示。<br>正式版创建成功后，直接回到今天。</p></div>`;
 if(variant==='a')return `<p class="utterance">${esc(state.utterance)}</p><div class="stage"><button class="mascot-button" data-action="hello" aria-label="和念念打招呼">${mascot()}</button></div><p class="role">念念和你说定</p>${proposalMarkup()}${composerMarkup()}`;
 if(variant==='b')return `<div class="chat-day">一件小事，也有人记得</div><p class="user-bubble">${esc(state.utterance)}</p><div class="chat-identity">${mascot()}<span>念念 <small>在听</small></span></div><div class="chat-reply">${proposalMarkup()}</div><div class="quick-replies"><button class="chip" data-delay="10">晚一点，10 分钟后</button><button class="chip" data-action="once">${state.repeat?'只提醒一次':'还是继续提醒我'}</button></div>${composerMarkup()}`;
 return `<div class="letter"><p class="eyebrow">一件小事 · 一个约定</p><p class="utterance">${esc(state.utterance)}</p>${proposalMarkup()}<div class="letter-signature"><p class="signature-text"><b>你先忙，我记着。</b><br>念念</p><button class="mascot-button" data-action="hello" aria-label="和念念打招呼">${mascot()}</button></div></div>${composerMarkup()}`;
}
function render(){
 app.innerHTML=`<div class="demo-bar">交互样稿 ${variant.toUpperCase()} · 不会创建真实提醒</div><header class="topbar"><a class="back" href="index.html" target="_top" aria-label="返回方案对比"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m15 5-7 7 7 7"/></svg>返回</a><span class="brand">${variant==='b'?'念念':'播下一件小事'}</span><button class="quiet" data-action="reset">重来</button></header><main class="content">${mainMarkup()}</main><footer class="footer">${state.phase==='saved'?'<button class="primary" data-action="reset">再体验一次</button><p class="footnote">仅演示界面，没有发送通知或增加水滴。</p>':`<button class="primary" data-action="confirm" ${state.busy?'disabled':''}>${state.permission?'好，就这样提醒我':'先开启通知'}</button>${variant==='b'?'<button class="chat-composer-trigger" data-action="compose"><span>再跟念念说一句…</span><span aria-hidden="true">↗</span></button>':`<button class="secondary" data-action="compose">${state.composeOpen?'收起修改':'我想改一下'}</button>`}<p class="footnote">确认后开始提醒</p>`}</footer>`;
 app.querySelector('.details')?.addEventListener('toggle',event=>{state.detailsOpen=event.target.open;});
 app.querySelector('#revision')?.addEventListener('input',event=>{state.message=event.target.value;});
 app.querySelector('#task-title')?.addEventListener('change',event=>{const title=event.target.value.trim();if(title)state.title=title;render();});
 app.querySelector('#repeat-rule')?.addEventListener('change',event=>{state.repeat=event.target.value!=='0';if(state.repeat)state.interval=Number(event.target.value);render();});
 app.querySelector('#end-at')?.addEventListener('change',event=>{const value=event.target.value;if(value && new Date(value+'+08:00').getTime()<=firstTime().getTime()){toast('截止时间要晚于首次提醒。');event.target.value=state.endAt;return;}state.endAt=value;render();});
}
function toast(message){clearTimeout(toastTimer);document.querySelector('.toast')?.remove();const el=document.createElement('div');el.className='toast';el.setAttribute('role','status');el.textContent=message;document.body.append(el);toastTimer=setTimeout(()=>el.remove(),2200);}
function reset(){clearTimeout(replyTimer);Object.assign(state,{delay:3,interval:5,repeat:true,title:'点外卖',utterance:'待会儿记得提醒我点外卖',timeOpen:false,detailsOpen:false,composeOpen:false,message:'',error:'',pose:'speak',phase:'proposal',permission:true,complex:false,endAt:'',busy:false});render();window.scrollTo(0,0);}
function revise(){
 const message=state.message.trim();const delay=message.match(/(?:改成|改到|晚点|晚一点)[\s，,]*(\d+)\s*分钟后/);const interval=message.match(/每\s*(\d+)\s*分钟/);const title=message.match(/^提醒我\s*(.{1,60})$/);const once=/只提醒一次/.test(message);
 if(!delay&&!interval&&!title&&!once){state.error='这个样稿还听不懂这句话，可以点上面的例子试试。';render();return;}
 if((delay&&(Number(delay[1])<1||Number(delay[1])>1440))||(interval&&(Number(interval[1])<1||Number(interval[1])>120))){state.error='示例时间范围：1–1440 分钟后，提醒间隔 1–120 分钟。';render();return;}
 state.busy=true;state.pose='think';state.error='';render();
 replyTimer=setTimeout(()=>{if(delay){state.delay=Number(delay[1]);state.complex=false;}if(interval){state.interval=Number(interval[1]);state.repeat=true;state.complex=false;}if(title)state.title=title[1].replace(/[。！!]+$/,'');if(once){state.repeat=false;state.complex=false;}state.busy=false;state.composeOpen=false;state.message='';state.pose='speak';render();toast('已经按你的意思改好了。');},650);
}
app.addEventListener('click',event=>{
 const button=event.target.closest('button');if(!button||button.disabled||state.busy)return;
 if(button.dataset.delay){state.delay=Number(button.dataset.delay);state.complex=false;state.timeOpen=false;render();return;}
 if(button.dataset.message){state.message=button.dataset.message;state.error='';render();app.querySelector('#revision')?.focus();return;}
 switch(button.dataset.action){
 case 'time':state.timeOpen=!state.timeOpen;render();break;
 case 'compose':state.composeOpen=!state.composeOpen;state.error='';render();if(state.composeOpen){app.querySelector('#revision')?.focus();app.querySelector('.composer')?.scrollIntoView({block:'nearest',behavior:'smooth'});}break;
 case 'once':state.repeat=!state.repeat;state.complex=false;render();break;
 case 'revise':revise();break;
 case 'hello':state.pose=state.pose==='happy'?'speak':'happy';render();toast('嗯，我在听。');break;
 case 'permission':state.permission=true;render();toast('已模拟开启，草稿还在。');break;
 case 'confirm':if(!state.permission){toast('点提示里的“模拟开启通知”，继续体验。');return;}if(state.endAt&&new Date(state.endAt+'+08:00').getTime()<=firstTime().getTime()){state.detailsOpen=true;render();toast('截止时间已早于首次提醒，请调整。');return;}state.phase='saved';state.confirmedAt=firstTime();state.pose='happy';render();window.scrollTo(0,0);break;
 case 'reset':reset();break;
 }
});
function updateMotion(){document.documentElement.classList.toggle('motion-paused',manualPaused||document.hidden);}
window.addEventListener('message',event=>{if(event.source!==window.parent || !event.data || event.data.type!=='niannian-prototype')return;const action=event.data.action;if(action==='reset')reset();if(action==='permission'){reset();state.permission=false;render();}if(action==='complex'){reset();state.title='吃早餐';state.utterance='工作日早上8点半提醒我吃早餐';state.complex=true;render();}if(action==='motion'){manualPaused=!event.data.enabled;updateMotion();}});
document.addEventListener('visibilitychange',updateMotion);
render();
