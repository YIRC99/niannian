import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const root=path.resolve(import.meta.dirname,'..');
const folder=path.join(root,'docs/benchmarks/runs/2026-10-08-clarification');
const oldFolder=path.join(root,'docs/benchmarks/runs/2026-10-08-colloquial');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const suite=read(path.join(root,'docs/benchmarks/reminder-creation-colloquial-v2.json'));
const review=read(path.join(folder,'review.json'));
const oldReview=read(path.join(oldFolder,'review.json'));
const rounds=[1,2,3].map(i=>read(path.join(folder,`round-${i}.json`)));
const oldRounds=[1,2,3].map(i=>read(path.join(oldFolder,`round-${i}.json`)));
const conversations=read(path.join(folder,'conversations.json'));
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pretty=o=>esc(JSON.stringify(o,null,2));
const sum=a=>a.reduce((x,y)=>x+y,0),mean=a=>sum(a)/a.length,f=n=>n.toFixed(1);
const scores=(r,i)=>Math.min(sum(r.checks.map(c=>c.scores[i])),r.score_caps?.[i]??10);
const rows=suite.cases.map(tc=>{
 const r=review.cases.find(c=>c.id===tc.id),old=oldReview.cases.find(c=>c.id===tc.id);
 if(!r||r.checks.length!==5||r.checks.some(c=>c.scores.length!==3||c.scores.some(n=>![0,1,2].includes(n))))throw Error('Missing scored evidence '+tc.id);
 const samples=rounds.map(x=>x.cases.find(c=>c.id===tc.id));
 for(const sample of samples){
  if(!sample||sample.input!==tc.input||sample.now!==tc.now)throw Error('Changed fixture '+tc.id);
  if(sample.mail_count||Object.values(sample.side_effect_counts).some(n=>n!==0))throw Error('Unexpected side effect');
 }
 return {...tc,review:r,oldReview:old,samples,oldSamples:oldRounds.map(x=>x.cases.find(c=>c.id===tc.id)),scores:[0,1,2].map(i=>scores(r,i)),oldScores:[0,1,2].map(i=>scores(old,i))};
});
const totals=[0,1,2].map(i=>sum(rows.map(r=>r.scores[i]))),oldTotals=[0,1,2].map(i=>sum(rows.map(r=>r.oldScores[i])));
const usage=rs=>{
 const calls=rs.flatMap(r=>r.cases.flatMap(c=>c.provider_calls));
 const aggregate={calls:calls.length,requests:sum(rs.map(r=>r.cases.length))};
 for(const k of ['prompt_tokens','completion_tokens','total_tokens','prompt_cache_hit_tokens','prompt_cache_miss_tokens'])aggregate[k]=sum(calls.map(c=>{if(!Number.isFinite(c.usage?.[k]))throw Error('Missing usage '+k);return c.usage[k]}));
 return aggregate;
};
const before=usage(oldRounds),after=usage(rounds),tokenChange=100*(1-after.total_tokens/before.total_tokens);
const response=s=>{
 const d=s.response||{},q=d.clarification;
 if(q)return `<p class="badge">待补充 · 尚未生成草稿</p><h4>${esc(q.question)}</h4>${q.options.length?`<ul>${q.options.map(o=>`<li><b>${esc(o.label)}</b>：${esc(o.value)}</li>`).join('')}</ul>`:'<p>本题需要真实日期与时刻，返回自由输入。</p>'}<p class="muted">${esc(q.input_hint)}</p>`;
 if(!d.rule)return `<p class="error">${esc(d.error||'没有有效草稿')}</p>`;
 return `<h4>${esc(d.title)}</h4><p>${esc(d.summary)}</p>${d.description?`<p>备注：${esc(d.description)}</p>`:''}<ul>${(d.warnings||[]).map(w=>`<li>${esc(w)}</li>`).join('')}</ul>`;
};
const caseHTML=rows.map(row=>`<details class="case" id="${row.id}" ${['RC-08','RC-09','RC-10'].includes(row.id)?'open':''}>
<summary><span>${row.id} · ${esc(row.input)}</span><b>${f(mean(row.scores))} / 10</b></summary><div class="inside">
<p class="finding">${esc(row.review.headline)}</p><p class="muted">固定当前时间：${esc(row.now)} · 每轮独立请求</p>
<label>采样轮次 <select data-case="${row.id}">${row.scores.map((n,i)=>`<option value="${i}">第 ${i+1} 轮 · ${n}/10</option>`).join('')}</select></label>
${row.samples.map((s,i)=>`<section class="sample" data-sample="${row.id}-${i}" ${i?'hidden':''}><div class="compare"><section><h3>2.6.1 实际回答 · ${row.oldScores[i]}/10</h3>${response(row.oldSamples[i])}</section><section><h3>2.6.2 实际回答 · ${row.scores[i]}/10</h3>${response(s)}<p class="muted">HTTP ${s.http_status} · ${s.provider_calls.length} 次模型调用 · ${(s.elapsed_ms/1000).toFixed(2)} 秒</p></section></div>
<details><summary>完整响应、模型原始输出与用量</summary><pre>${pretty(s)}</pre></details></section>`).join('')}
<details><summary>五项评分依据</summary><div class="scroll"><table><thead><tr><th>标准</th><th>三轮得分</th><th>审阅依据</th></tr></thead><tbody>${row.review.checks.map((c,i)=>`<tr><th>${esc(suite.dimensions[i])}<small>${esc(row.checks[i])}</small></th><td>${c.scores.join(' / ')}</td><td>${esc(c.evidence)}</td></tr>`).join('')}</tbody></table></div></details>
<details><summary>上次报告的更合适说法（对照目标）</summary><blockquote>${esc(row.oldReview.ideal_example)}</blockquote><p class="muted">这是此前的人工示例，不冒充本轮实际输出。</p></details>
</div></details>`).join('');
const conversationNames={free_text:'输入真实日期与时间',timezone_choice:'直接选择纽约时区',midnight:'原话跨午夜后继续',partial_then_correction:'分两轮补齐并改口',prior_answer_midnight:'此前补充也跨午夜'};
const flows=conversations.cases.map(c=>`<details class="flow"><summary>${esc(conversationNames[c.id]||c.id)} · ${c.passed?'通过':'未通过'} · ${c.steps.length} 轮</summary><div class="inside"><p><b>原话：</b>${esc(c.input)}</p>${c.steps.map((s,i)=>`<section class="step"><h3>第 ${i+1} 轮 <small>${esc(s.now)}</small></h3>${i?`<p class="answer">用户补充：${esc(s.request.answers.at(-1).answer)}</p>`:''}${response(s)}<p class="muted">${s.provider_calls.length} 次模型调用</p><details><summary>本轮原始证据</summary><pre>${pretty(s)}</pre></details></section>`).join('')}<p class="muted">业务表新增 ${sum(Object.values(c.side_effect_counts))} · 邮件 ${c.mail_count}</p></div></details>`).join('');
const script=`document.querySelectorAll('select[data-case]').forEach(s=>s.addEventListener('change',()=>{document.getElementById(s.dataset.case).querySelectorAll('.sample').forEach(p=>p.hidden=p.dataset.sample!==s.dataset.case+'-'+s.value)}));document.querySelectorAll('a[href^="#RC-"]').forEach(a=>a.addEventListener('click',()=>document.querySelector(a.getAttribute('href')).open=true));document.getElementById('all').onclick=()=>document.querySelectorAll('.case').forEach(d=>d.open=true);document.getElementById('search').addEventListener('input',e=>document.querySelectorAll('.case').forEach(d=>d.hidden=!d.textContent.toLowerCase().includes(e.target.value.toLowerCase())));`;
new Function(script);
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>念念 · 创建提醒与追问评测</title><style>
:root{color-scheme:light;--bg:#f5f7f1;--paper:#fffdf7;--ink:#263e31;--muted:#627565;--line:#d7e0d2;--green:#3b6548}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.8 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1180px;margin:auto;padding:38px 28px 80px}header{padding:12px 0 26px;border-bottom:1px solid var(--line)}h1{font-size:34px;line-height:1.35;margin:12px 0}h2{font-size:24px;margin:42px 0 16px}h3{font-size:16px;margin:14px 0}h4{font-size:19px;margin:8px 0}p{margin:10px 0}.lead{max-width:75ch;font-size:18px}a{color:var(--green);text-underline-offset:4px}.muted,small{color:var(--muted);font-size:13px}small{display:block}nav{display:flex;gap:20px;flex-wrap:wrap;margin:20px 0}.badge{display:inline-block;border:1px solid #adc5a9;background:#e7efdf;padding:2px 10px;border-radius:20px;font-size:13px}.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;text-align:left;font-size:14px}td,th{padding:13px 12px;border-bottom:1px solid var(--line);vertical-align:top}thead{background:#eaf0e3}th{font-weight:600}.compare{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-top:18px}.compare>section{padding:4px 0}.compare>section+section{border-left:1px solid var(--line);padding-left:24px}.case,.flow{background:var(--paper);border:1px solid var(--line);border-radius:14px;margin:14px 0;overflow:hidden}.case>summary,.flow>summary{padding:18px 20px;cursor:pointer;font-weight:600}.case>summary b{float:right;color:var(--green);padding-left:15px}.inside{padding:0 20px 20px}.finding{font-weight:600}.inside details{border-top:1px solid var(--line);margin-top:14px;padding-top:8px}summary{cursor:pointer}li{margin:7px 0}ul{padding-left:22px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 ui-monospace,Consolas,monospace;background:#eef2e8;padding:16px;max-height:520px;overflow:auto;border-radius:8px}blockquote{margin:16px 0;padding:16px;background:#edf2e7;border-radius:10px}input,select,button{font:inherit;border:1px solid #a8bfa6;border-radius:8px;background:var(--paper);color:var(--ink);padding:8px 12px;min-height:44px}button{cursor:pointer}.toolbar{display:flex;gap:12px;flex-wrap:wrap}.error{color:#973f37}.step{margin:22px 0;padding-bottom:20px;border-bottom:1px solid var(--line)}.answer{background:#e7efdf;padding:10px 14px;border-radius:8px}[hidden]{display:none!important}.note{padding:18px 20px;background:#eaf0e3;border:1px solid var(--line);border-radius:12px}footer{margin-top:48px;border-top:1px solid var(--line);padding-top:20px} @media(max-width:720px){main{padding:20px 14px 48px}h1{font-size:27px}.compare{grid-template-columns:1fr}.compare>section+section{padding-left:0;border-left:0;border-top:1px solid var(--line)}.case>summary b{float:none;display:block;padding-left:0}.inside{padding:0 14px 16px}td,th{padding:10px 8px;min-width:100px}}@media print{.toolbar,nav{display:none}pre{max-height:none}.case,.flow{break-inside:avoid}body{background:var(--paper)}}
</style><main><header><span class="badge">念念 2.6.2 · 真实模型评测</span><h1>会先问清楚，再把事情安排好</h1><p class="lead">相同的 10 句口语需求，固定时间、各跑 3 轮。平均得分从 <b>${f(mean(oldTotals))}</b> 提升至 <b>${f(mean(totals))}/100</b>；本组平均总 token ${tokenChange>=0?'减少':'增加'} <b>${f(Math.abs(tokenChange))}%</b>。</p><p class="muted">生产代码 ${esc(review.source_commit)} · deepseek-flash · 未开启深度思考 · 每次最多两次模型调用</p></header>
<nav><a href="#overview">结果总览</a><a href="#tokens">Token 对比</a><a href="#cases">10 个案例</a><a href="#flows">追问完整过程</a><a href="#scope">验证范围</a></nav>
<section id="overview"><h2>改善在哪里，哪里还有不足</h2><p>${esc(review.summary)}</p><ul>${review.findings.map(x=>`<li><b>${esc(x.title)}</b>：${esc(x.detail)}</li>`).join('')}</ul><div class="scroll"><table><thead><tr><th>案例</th><th>原话</th><th>之前均分</th><th>现在三轮</th><th>现在均分</th></tr></thead><tbody>${rows.map(r=>`<tr><td><a href="#${r.id}">${r.id}</a></td><th>${esc(r.input)}</th><td>${f(mean(r.oldScores))}</td><td>${r.scores.join(' / ')}</td><td>${f(mean(r.scores))}</td></tr>`).join('')}</tbody><tfoot><tr><th colspan="2">总分 / 100</th><td>${f(mean(oldTotals))}</td><td>${totals.join(' / ')}</td><td>${f(mean(totals))}</td></tr></tfoot></table></div></section>
<section id="tokens"><h2>没有靠增加模型调用换效果</h2><p>只比较首轮相同 30 次请求；下方 5 组多轮对话单独列出，不混进首轮节省比例。缓存会影响实际账单，这里不把 token 降幅冒充费用降幅。</p><div class="scroll"><table><thead><tr><th>每次请求平均</th><th>2.6.1</th><th>2.6.2</th></tr></thead><tbody>${[['prompt_tokens','输入 token'],['completion_tokens','输出 token'],['total_tokens','总 token'],['prompt_cache_hit_tokens','缓存命中 token'],['prompt_cache_miss_tokens','未命中 token'],['calls','模型调用']].map(([key,label])=>`<tr><th>${label}</th><td>${f(before[key]/30)}</td><td>${f(after[key]/30)}</td></tr>`).join('')}</tbody></table></div><p class="muted">缺少面试时间时直接返回问题，正常草稿仍做一次复核。真实 provider usage、全部响应均可在各题中展开。</p></section>
<section id="cases"><h2>10 个案例 · 逐题看真实回答</h2><div class="toolbar"><input id="search" aria-label="筛选案例" placeholder="搜索案例或问题"><button id="all">展开所有案例</button></div>${caseHTML}</section>
<section id="flows"><h2>追问之后，能否接着安排</h2><p>${conversations.cases.filter(c=>c.passed).length}/${conversations.cases.length} 组通过。这里直接展示后端返回的选项、实际提交的答案和最后草稿；不是手机截图，也没有创建提醒。</p>${flows}</section>
<section id="scope"><h2>验证范围与证据</h2><div class="note"><p><b>真实模型 + 生产 HTTP Handler。</b>注入固定时钟，临时数据库和测试邮件器；没有启动监听服务或调度器。既有运行中的 2.6.1 服务未替换，本报告不冒充已部署的 2.6.2 联网结果。</p><p>Android 2.6.2 / versionCode 35：选项、自由输入、失败重试、账号清理、页面重建已开发。构建、Lint 和 Java 逻辑检查已完成；手机暂不可连接，安装、视觉和触控尚未验证。</p><p>${esc(review.validation)}</p></div><p>评分沿用事先冻结的五项标准，每项 0 / 1 / 2 分，由本次开发智能体按原始证据审阅，并非独立盲评或模型自动判分。题目用于调优，属于回归样本，不能代表未见需求的泛化准确率。</p><p>调优过程中发现的问题没有删除：保留 <a href="iteration-364c1a6/round-1.json">第一候选</a> 和 <a href="iteration-ec713ab/round-1.json">第二候选</a> 的全部三轮原始数据。最后一版固定代码后整组采样，未用单题重跑替换失败。${esc(review.iterations)}</p><p><a href="review.json">评分依据 JSON</a> · <a href="metrics.json">统计与 token</a> · <a href="round-1.json">第 1 轮原始结果</a> · <a href="round-2.json">第 2 轮</a> · <a href="round-3.json">第 3 轮</a> · <a href="conversations.json">多轮对话结果</a> · <a href="../2026-10-08-colloquial/index.html">优化前报告</a></p></section><footer class="muted">全部内容在本地，可离线打开。需要升级时请同时使用新版 Android 与后端，保留原数据和私人配置。</footer></main><script>${script}</script></html>`;
fs.writeFileSync(path.join(folder,'index.html'),html);
fs.writeFileSync(path.join(folder,'metrics.json'),JSON.stringify({source_commit:review.source_commit,before_scores:oldTotals,after_scores:totals,before_mean:mean(oldTotals),after_mean:mean(totals),before_usage:before,after_usage:after,token_reduction_percent:tokenChange,conversation_passes:conversations.cases.filter(c=>c.passed).length,conversation_count:conversations.cases.length},null,2)+'\n');
const files=['review.json','metrics.json','round-1.json','round-2.json','round-3.json','conversations.json','conversations-before-question-clock.json','index.html',...['iteration-364c1a6','iteration-ec713ab'].flatMap(dir=>fs.readdirSync(path.join(folder,dir)).filter(f=>f.endsWith('.json')).map(f=>`${dir}/${f}`))];
fs.writeFileSync(path.join(folder,'manifest.json'),JSON.stringify({source_commit:review.source_commit,suite_sha256:rounds[0].suite_sha256,files:files.map(name=>({name,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(folder,name))).digest('hex')}))},null,2)+'\n');
console.log(JSON.stringify({scores:totals,mean:mean(totals),tokenReduction:tokenChange,path:path.join(folder,'index.html')}));
