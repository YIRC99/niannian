import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const run = path.join(root,'docs/benchmarks/runs/2026-10-08-colloquial');
const suiteFile = path.join(root,'docs/benchmarks/reminder-creation-colloquial-v2.json');
const read = file => JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const suite = read(suiteFile), review = read(path.join(run,'review.json'));
const original = read(path.join(root,'docs/benchmarks/reminder-creation-v1.json'));
const rounds = [1,2,3].map(n=>read(path.join(run,`round-${n}.json`)));
const live = read(path.join(run,'http-results.json'));
if(live.cases.length!==10||new Set(live.cases.map(c=>c.id)).size!==10||live.cleanup!=='temporary_account_deleted_verified_404'||live.task_count_after!==0||live.suite_sha256!==hash(suiteFile))throw Error('Live HTTP run incomplete or unclean');
const esc = v => String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pretty = v => esc(JSON.stringify(v,null,2));
const mean = values => values.reduce((a,b)=>a+b,0)/values.length;
const fixed = n => Number.isInteger(n)?String(n):n.toFixed(1);
const sec = ms => (ms/1000).toFixed(2)+' 秒';
for(const data of [suite,review,original,...rounds]) if(data.cases.length!==10||new Set(data.cases.map(c=>c.id)).size!==10) throw Error('Expected 10 unique cases');
for(const data of rounds) if(data.suite_sha256!==hash(suiteFile)) throw Error('Frozen suite mismatch');
const rows = suite.cases.map(tc=>{
  const old = original.cases.find(c=>c.id===tc.id), r=review.cases.find(c=>c.id===tc.id);
  const samples=rounds.map(d=>d.cases.find(c=>c.id===tc.id));
  if(!old||!r||r.checks.length!==5||r.checks.some(c=>c.scores.length!==3||c.scores.some(s=>![0,1,2].includes(s)))) throw Error('Incomplete review '+tc.id);
  for(const s of samples){
    if(!s||s.input!==tc.input||s.now!==tc.now)throw Error('Input or clock mismatch '+tc.id);
    if(Object.keys(s.side_effect_counts).length!==5||Object.values(s.side_effect_counts).some(n=>n!==0)||s.mail_count!==0)throw Error('Unexpected side effects '+tc.id);
    if(s.provider_calls.length>2)throw Error('Unexpected extra provider call');
  }
  const scores=samples.map((_,i)=>Math.min(r.checks.reduce((n,c)=>n+c.scores[i],0),r.score_caps?.[i]??10));
  const http=live.cases.find(c=>c.id===tc.id);
  if(!http||http.input!==tc.input)throw Error('Live input mismatch '+tc.id);
  return {...tc,old,review:r,samples,scores,average:mean(scores),http};
});
const totals=[0,1,2].map(i=>rows.reduce((n,c)=>n+c.scores[i],0));
const overall=mean(totals), dimensionTotals=suite.dimensions.map((_,i)=>rows.reduce((n,c)=>n+c.review.checks[i].scores.reduce((a,b)=>a+b,0),0));
const all=rows.flatMap(c=>c.samples), durations=all.map(c=>c.elapsed_ms).sort((a,b)=>a-b);
const median=(durations[14]+durations[15])/2;
const models=[...new Set(all.flatMap(c=>c.provider_calls.map(p=>p.model)))];
const liveSuccess=live.cases.filter(c=>c.http_status===200).length;
const liveEvidence=`<section class="method"><h2>补充：直接访问运行中的后端</h2><p><strong>${liveSuccess}/10返回草稿，1次返回错误。</strong>服务器是${esc(live.endpoint)}，health版本${esc(live.health.version)}，请求发生在${esc(live.started_at)}至${esc(live.finished_at)}。使用同样10句原话和实际时钟，不计入上面的固定时间评分。</p><p>交材料一题请求时已经过17点，返回HTTP 502与“没有未来提醒时间”；还相机正确改为从明天中午开始。面试缺时间却猜日期、下午窗口无截止也在真实接口重现。每题底部可展开真实HTTP返回。</p><p class="muted">一个临时测试账号，生成后任务数0；账号删除后读取返回404。未保存任何提醒。当前磁盘二进制构建提交${esc(review.live_process?.vcs_revision)}，modified=false，SHA-256为${esc(review.live_process?.binary_sha256)}。服务由本轮之外启动，本轮未启动或替换。</p><p><a href="http-results.json" download>下载10次真实接口完整结果</a></p></section>`;
const charLength = s => [...s].length;
const meanOld=mean(rows.map(c=>charLength(c.old.input))), meanNew=mean(rows.map(c=>charLength(c.input)));
const baseline=path.join(root,'docs/benchmarks/runs/2026-10-08/index.html');
const css=fs.readFileSync(baseline,'utf8').match(/<style>([\s\S]*?)<\/style>/)?.[1];
if(!css)throw Error('Missing report style');
const overview=rows.map(c=>`<tr><td><a href="#${c.id}">${c.id}</a></td><th scope="row">${esc(c.input)}</th><td>${c.scores.join(' / ')}</td><td><strong>${fixed(c.average)}</strong></td><td>${esc(c.review.headline)}</td></tr>`).join('');
const dimensions=suite.dimensions.map((name,i)=>`<tr><th scope="row">${esc(name)}</th><td>${dimensionTotals[i]} / 60</td><td><meter min="0" max="60" value="${dimensionTotals[i]}" aria-label="${esc(name)} ${dimensionTotals[i]}/60"></meter></td></tr>`).join('');
const issues=review.findings.map(f=>`<li><b>${esc(f.title)}</b><p>${esc(f.evidence)}</p><p class="muted">${esc(f.recommendation)}</p></li>`).join('');
const cases=rows.map(c=>`<details class="case" id="${c.id}" ${['RC-06','RC-08','RC-09'].includes(c.id)?'open':''}>
<summary><span class="number">${c.id}</span><span class="case-title">${esc(c.input)}<small>${esc(c.name)}</small></span><strong class="points">${fixed(c.average)}<small>/10</small></strong></summary>
<div class="case-body"><p class="finding">${esc(c.review.headline)}</p><p class="muted">固定当前时间 ${esc(c.now)} · 无历史对话、日历或作息信息</p>
<details class="sub"><summary>原题改成了什么，省略的信息是什么</summary><p><strong>原来：</strong>${esc(c.old.input)}</p><p><strong>这次：</strong>${esc(c.input)}</p><p><strong>没有交代：</strong>${esc(c.missing_information.join('、'))}</p><p><strong>测试前定好的期望：</strong>${esc(c.acceptable_outcomes)}</p><p>${esc(c.interaction_policy)}</p></details>
<div class="toolbar"><label for="sample-${c.id}">查看实际返回：</label><select id="sample-${c.id}" data-case="${c.id}" aria-label="${c.id}选择采样轮次">${c.samples.map((s,i)=>`<option value="${i}">第${i+1}轮 · ${c.scores[i]}/10 · ${sec(s.elapsed_ms)}</option>`).join('')}</select></div>
${c.samples.map((s,i)=>`<section class="sample" data-sample="${c.id}-${i}" ${i?'hidden':''}>
<div class="two-columns"><section><h3>后端真实返回 · 第${i+1}轮</h3><p class="actual-title">${esc(s.response?.title||s.response?.error||'无草稿')}</p><p>${esc(s.response?.summary)}</p>${s.response?.description?`<p>备注：${esc(s.response.description)}</p>`:''}<ul class="feedback">${(s.response?.warnings||[]).map(w=>`<li>${esc(w)}</li>`).join('')}</ul><p class="muted">下一规则候选：${esc(s.next_candidate||'无')}<br>HTTP ${s.http_status} · 模型调用 ${s.provider_calls.length} 次 · ${sec(s.elapsed_ms)}</p></section>
<section><h3>更合适的说法示例</h3><p class="tag warn">人工改进示例，非本轮实际输出，未实现</p><blockquote>${esc(c.review.ideal_example)}</blockquote><p class="muted">${esc(c.interaction_policy)}</p></section></div>
<details class="sub"><summary>第${i+1}轮原始证据</summary><details class="raw"><summary>后端完整响应</summary><pre>${esc(s.raw_response)}</pre></details><details class="raw"><summary>模型两次返回</summary><pre>${pretty(s.provider_calls)}</pre></details><details class="raw"><summary>候选与业务副作用</summary><pre>${pretty({candidate_preview:s.candidate_preview,side_effect_counts:s.side_effect_counts,mail_count:s.mail_count})}</pre></details></details></section>`).join('')}
<details class="sub"><summary>冻结的五项标准与三轮评分依据</summary><div class="table-scroll"><table><thead><tr><th>评分项</th><th>第1 / 2 / 3轮</th><th>证据</th></tr></thead><tbody>${c.review.checks.map((check,i)=>`<tr><th scope="row">${esc(suite.dimensions[i])}<p class="muted">${esc(c.checks[i])}</p></th><td>${check.scores.join(' / ')}<br><small>每次满分2</small></td><td>${esc(check.evidence)}</td></tr>`).join('')}</tbody></table></div>${c.review.cap_reason?`<p class="note">${esc(c.review.cap_reason)}</p>`:''}</details>
<details class="sub"><summary>运行中后端的真实HTTP返回 · ${c.http.http_status} · 不计入固定时间分数</summary><p class="note">${esc(review.live_http_observations?.[c.id]||'使用实际服务器时钟，不按原固定日期打分。')}</p><p class="muted">实际请求时间 ${esc(c.http.request_started_at)} · ${sec(c.http.elapsed_ms)}</p><p><strong>${esc(c.http.response?.title||c.http.response?.error)}</strong></p><p>${esc(c.http.response?.summary)}</p><ul>${(c.http.response?.warnings||[]).map(w=>`<li>${esc(w)}</li>`).join('')}</ul><details class="raw"><summary>真实接口完整响应</summary><pre>${esc(c.http.raw_response)}</pre></details></details>
</div></details>`).join('');
const script=`document.querySelectorAll('select[data-case]').forEach(s=>s.addEventListener('change',()=>{const card=document.getElementById(s.dataset.case);card.querySelectorAll('.sample').forEach(p=>p.hidden=p.dataset.sample!==s.dataset.case+'-'+s.value)}));document.querySelectorAll('a[href^="#RC-"]').forEach(a=>a.addEventListener('click',()=>document.querySelector(a.getAttribute('href')).open=true));document.getElementById('expand').onclick=()=>document.querySelectorAll('.case').forEach(c=>c.open=true);document.getElementById('collapse').onclick=()=>document.querySelectorAll('.case').forEach(c=>c.open=false);`;
new Function(script);
const html=`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>念念 · 真实短口语创建提醒评测</title><style>${css}
.overview{min-width:850px}.overview tbody th{min-width:220px}.overview td:last-child{white-space:normal;min-width:290px}.two-columns section:first-child{padding:0}.sample .two-columns section:last-child{background:var(--soft)}select{font:inherit;color:var(--ink);background:var(--paper);border:1px solid var(--line);border-radius:6px;padding:9px 12px;min-height:44px}select:focus-visible{outline:2px solid var(--green);outline-offset:3px}meter{width:min(320px,100%);height:16px;accent-color:var(--green)}.checks-table{max-width:700px}.priorities p{font-size:14px;margin:5px 0}.case-body .table-scroll th{min-width:230px}.case-body .table-scroll td:last-child{min-width:260px}@media print{.sample[hidden]{display:none!important}}
</style></head><body><main class="page"><div class="topline"><span class="brand">念念 / 短口语与缺失信息评测</span><span>2026-10-08 · 当前后端2.6.1源码 · 30次真实模型采样</span></div>
<header><h1>用户只说一句话，念念能补到哪一步？</h1><p>10题全部改为日常短口语，平均从${fixed(meanOld)}字缩到${fixed(meanNew)}字。每题连续测3次，新标准平均 <strong>${fixed(overall)}/100</strong>。日常待办能理解，但“该追问的事件时间”和“没明说完整的场景限制”仍是明显短板。</p></header>
<div class="statline"><span>三轮得分 <strong>${totals.join(' / ')}</strong></span><span>平均 <strong>${fixed(overall)}/100</strong></span><span>返回草稿 <strong>${all.filter(c=>c.http_status===200).length}/30</strong></span><span>耗时中位数 <strong>${sec(median)}</strong></span></div>
<div class="notice"><strong>这次不考用户能不能把规则交代完整。</strong><br>允许多种合理建议。缺“中午几点”可以先建议12点；缺“面试哪天几点”必须问。通用的“请确认”不能代替澄清关键事实。${esc(suite.scoring.comparability)}</div>
<h2>先看你提到的还相机</h2><blockquote>“后面每天中午记得提醒我还相机。”</blockquote><p>三轮均安排从今天12点开始每天催，<strong>还完就永久停</strong>，没有变成每天重新归还相机。两轮没有明确说12点是补全建议，因此平均${fixed(rows.find(c=>c.id==='RC-06').average)}/10。<a href="#RC-06">查看三次原始返回</a></p>
<h2>这轮真正暴露的问题</h2><ol class="priorities">${issues}</ol>
<h2>五个维度，哪里最薄弱</h2><div class="checks-table"><table><thead><tr><th>维度</th><th>30次合计</th><th>得分</th></tr></thead><tbody>${dimensions}</tbody></table></div><p class="muted">每次每维度0—2分；合理性和沟通体验包含人工判断，分数不是成功率。</p>
<h2>10条真实口语，一览看完</h2><div class="table-scroll"><table class="overview"><thead><tr><th>题号</th><th>实际输入原话</th><th>第1 / 2 / 3轮</th><th>平均 /10</th><th>观察</th></tr></thead><tbody>${overview}</tbody></table></div>
<h2>逐题看创建效果</h2><p class="muted">每题可切换三轮返回，展开原题、评分标准、模型原始输出。改进示例单独标明，不参与评分。</p><div class="toolbar"><button id="expand" type="button">展开题目</button><button id="collapse" type="button">收起题目</button></div>${cases}
<section class="method"><h2>方法、证据与限制</h2><ul><li><strong>先定题再运行：</strong>题目与评分提交 ${esc(review.rubric_commit)}；原生产代码基线 ${esc(review.production_baseline)}，评测中没有修改生产提示或业务实现。${esc(review.sampling)}</li><li><strong>实际怎么测：</strong>${esc(review.execution_scope)} 提供方返回模型 ${esc(models.join('、'))}，总计${all.reduce((n,c)=>n+c.provider_calls.length,0)}次模型请求。只把短原话和当前时间给模型，没有提供答案、理想示例或评分标准。</li><li><strong>如何评分：</strong>${esc(review.review_method)} ${esc(suite.scoring.aggregation)}</li>${review.scoring_notes.map(n=>`<li>${esc(n)}</li>`).join('')}<li><strong>无业务副作用：</strong>30次的任务、通知、完成、提醒计划、花园奖励与测试邮件新增均为0。没有保存提醒或验证手机通知/闹钟/日历/邮件到达，候选时间不是实际送达记录。</li><li><strong>范围：</strong>本轮完成评测重写与复测，没有修复上述新缺口、更新后端部署或修改手机页面。没有把单次字段合法视为场景正确，也未进行无历史接口无法支持的多轮澄清实测。</li></ul>
<div class="downloads"><a href="../../reminder-creation-colloquial-v2.json" download>新10题与冻结评分</a><a href="review.json" download>评分和问题分析</a><a href="round-1.json" download>第1轮原始结果</a><a href="round-2.json" download>第2轮原始结果</a><a href="round-3.json" download>第3轮原始结果</a><a href="../2026-10-08-optimized/index.html">旧版详细题报告</a></div></section>
<footer>离线可用，不加载外部资源或统计脚本。报告保留所有30次结果；HTTP成功仅代表返回草稿，不代表理解正确。</footer></main><script>${script}</script></body></html>`;
fs.writeFileSync(path.join(run,'index.html'),html.replace('<section class="method">',liveEvidence+'<section class="method">').replace('报告保留所有30次结果','报告保留30次评分采样及10次真实接口结果').replace(/[ \t]+$/gm,'')+'\n');
const manifest={generated_at:new Date().toISOString(),source_commit:rounds[0].source_commit,suite_sha256:hash(suiteFile),round_totals:totals,average_score:overall,case_averages:Object.fromEntries(rows.map(c=>[c.id,c.average])),dimension_totals:Object.fromEntries(suite.dimensions.map((d,i)=>[d,dimensionTotals[i]])),http_success:all.filter(c=>c.http_status===200).length,sample_count:all.length,model_calls:all.reduce((n,c)=>n+c.provider_calls.length,0),median_elapsed_ms:median,average_input_characters:{old:meanOld,colloquial:meanNew},files:{}};
manifest.live_http={success:liveSuccess,total:live.cases.length,version:live.health.version,scored:false,cleanup:live.cleanup,task_count_after:live.task_count_after};
for(const name of ['round-1.json','round-2.json','round-3.json','http-results.json','review.json','index.html'])manifest.files[name]=hash(path.join(run,name));
fs.writeFileSync(path.join(run,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify(manifest,null,2));
