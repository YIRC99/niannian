import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const baseline = path.join(root, 'docs/benchmarks/runs/2026-10-08');
const run = path.resolve(process.argv[2] || path.join(root, 'docs/benchmarks/runs/2026-10-08-optimized'));
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const suitePath = path.join(root, 'docs/benchmarks/reminder-creation-v1.json');
const suite = read(suitePath), old = read(path.join(baseline, 'fixed-results.json'));
const oldReview = read(path.join(baseline, 'review.json')), oldHTTP = read(path.join(baseline, 'http-results.json'));
const current = read(path.join(run, 'fixed-results.json')), review = read(path.join(run, 'review.json'));
const score = r => r.checks.filter(c => c.pass).length * 2;
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pretty = v => esc(JSON.stringify(v, null, 2));
const sec = ms => (ms / 1000).toFixed(2) + ' 秒';
const median = list => { const s = [...list].sort((a,b)=>a-b); return (s[4]+s[5])/2; };
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
for (const group of [suite, old, oldReview, current, review]) {
  if (group.cases.length !== 10 || new Set(group.cases.map(c=>c.id)).size !== 10) throw Error('Expected 10 unique cases');
}
if (current.source_commit !== review.source_commit) throw Error('Source provenance mismatch');
const rows = suite.cases.map(tc => {
  const before = old.cases.find(c=>c.id===tc.id), after = current.cases.find(c=>c.id===tc.id);
  const bReview = oldReview.cases.find(c=>c.id===tc.id), aReview = review.cases.find(c=>c.id===tc.id);
  if (!before || !after || !bReview || !aReview || aReview.checks.length !== 5) throw Error(`Missing case ${tc.id}`);
  if (Object.values(after.side_effect_counts).some(n=>n!==0) || after.mail_count !== 0) throw Error('Unexpected side effects');
  if (after.input !== tc.input || before.input !== tc.input) throw Error(`Input changed: ${tc.id}`);
  return {...tc, before, after, bReview, aReview, beforeScore:score(bReview), afterScore:score(aReview)};
});
const oldTotal = rows.reduce((n,c)=>n+c.beforeScore,0), total = rows.reduce((n,c)=>n+c.afterScore,0);
const latency = median(rows.map(c=>c.after.elapsed_ms)), oldLatency = median(rows.map(c=>c.before.elapsed_ms));
const calls = rows.reduce((n,c)=>n+c.after.provider_calls.length,0), oldCalls = rows.reduce((n,c)=>n+c.before.provider_calls.length,0);
const success = rows.filter(c=>c.after.http_status===200).length;
const models = [...new Set(rows.flatMap(c=>c.after.provider_calls.map(p=>p.model)))];
const css = fs.readFileSync(path.join(baseline, 'index.html'), 'utf8').match(/<style>([\s\S]*?)<\/style>/)?.[1];
if (!css) throw Error('Baseline report styles unavailable');
const comparison = rows.map(c=>`<tr><td><a href="#${c.id}">${c.id}</a></td><th scope="row">${esc(c.name)}</th><td>${c.beforeScore} → <strong>${c.afterScore}</strong></td><td><span class="tag ${c.aReview.category==='boundary'?'warn':'good'}">${esc(c.aReview.verdict)}</span></td><td>${sec(c.after.elapsed_ms)}</td></tr>`).join('');
function expectedTable(c) {
  const expected = c.expected_rule_subset ?? {kind:'date_range',mode:'until_done',start_date:'2026-10-25',end_date:'2026-11-05',times:['09:00'],max_reminders:4};
  const render = v => esc(Array.isArray(v) ? JSON.stringify(v) : v === '' ? '未设置' : v);
  return `<p class="muted">${c.expected_rule_subset?'原始关键字段预期':'原题允许的第一种替代（自动循环与节假日仍未实现）'}</p><div class="table-scroll"><table class="rules"><thead><tr><th>字段</th><th>预期</th><th>优化后</th></tr></thead><tbody>${Object.entries(expected).map(([k,v])=>{
    const got=c.after.response?.rule?.[k] ?? (Array.isArray(v)?[]:typeof v==='number'?0:'');
    return `<tr class="${JSON.stringify(got)===JSON.stringify(v)?'':'diff'}"><th>${esc(k)}</th><td>${render(v)}</td><td>${render(got)}</td></tr>`;
  }).join('')}</tbody></table></div>`;
}
const details = rows.map(c=>`<details class="case" id="${c.id}" ${['RC-02','RC-10'].includes(c.id)?'open':''}>
<summary><span class="number">${c.id}</span><span class="case-title">${esc(c.name)}<small>${esc(c.aReview.verdict)}${c.aReview.exact_request_satisfied?'':' · 原需求未完整实现'}</small></span><strong class="points">${c.afterScore}<small>/10</small></strong></summary>
<div class="case-body"><p class="finding">${esc(c.aReview.headline)}</p><blockquote>${esc(c.input)}</blockquote><p class="muted">固定当前时间：${esc(c.now)}</p>
<div class="two-columns"><section><h3>优化前 · ${c.beforeScore}/10</h3><p>${esc(c.bReview.headline)}</p><p>${esc(c.before.response?.summary)}</p><ul>${(c.before.response?.warnings||[]).map(w=>`<li>${esc(w)}</li>`).join('')}</ul><p class="muted">下一候选：${esc(c.before.next_candidate)}<br>耗时 ${sec(c.before.elapsed_ms)}</p></section>
<section><h3>优化后 · ${c.afterScore}/10</h3><p class="actual-title">${esc(c.after.response?.title)}</p><p>${esc(c.after.response?.summary)}</p>${c.after.response?.description?`<p>备注：${esc(c.after.response.description)}</p>`:''}<ul class="feedback">${(c.after.response?.warnings||[]).map(w=>`<li>${esc(w)}</li>`).join('')}</ul><p class="muted">下一候选：${esc(c.after.next_candidate)}<br>耗时 ${sec(c.after.elapsed_ms)} · 模型调用 ${c.after.provider_calls.length} 次</p></section></div>
<details class="sub"><summary>原五项标准与得分依据</summary><ol class="checks">${c.aReview.checks.map((check,i)=>`<li><span class="tag ${check.pass?'good':'warn'}">${check.pass?'2':'0'}/2</span> <strong>${esc(c.scoring_checks[i])}</strong><p>${esc(check.evidence)}</p></li>`).join('')}</ol></details>
<details class="sub"><summary>关键字段对照</summary>${expectedTable(c)}</details>
<details class="sub"><summary>完整原始响应与模型调用</summary><details class="raw"><summary>后端最终响应 · HTTP ${c.after.http_status}</summary><pre>${esc(c.after.raw_response)}</pre></details>${c.after.provider_calls.map((p,i)=>`<details class="raw"><summary>模型第 ${i+1} 次原始响应 · ${esc(p.model)}</summary><pre>${pretty(p)}</pre></details>`).join('')}<details class="raw"><summary>调度候选预览与副作用检查</summary><pre>${pretty({next_candidate:c.after.next_candidate,candidate_preview:c.after.candidate_preview,side_effect_counts:c.after.side_effect_counts,mail_count:c.after.mail_count})}</pre></details></details></div></details>`).join('');
const script = `const cases=[...document.querySelectorAll('.case')];document.querySelector('#expand').onclick=()=>cases.forEach(c=>c.open=true);document.querySelector('#collapse').onclick=()=>cases.forEach(c=>c.open=false);document.querySelectorAll('a[href^="#RC-"]').forEach(a=>a.onclick=()=>{document.querySelector(a.getAttribute('href')).open=true});`;
new Function(script);
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>念念 · 创建提醒优化前后对比</title><style>${css}</style></head><body><main class="page">
<div class="topline"><span class="brand">念念 / 创建提醒复测</span><span>2026-10-08 · 后端 2.6.1 源码 · 真实模型返回</span></div>
<header><h1>原来的问题，这次修到了哪一步？</h1><p>沿用同一组复杂原话和评分标准，固定时间评分从 <strong>${oldTotal} 提升到 ${total}</strong>。窗口原始节奏、跨月完整日期、明确时区与可调整说明均通过本次复测。</p></header>
<div class="statline"><span>回归评分 <strong>${oldTotal} → ${total}/100</strong></span><span>返回草稿 <strong>${success}/10</strong></span><span>完整满足原需求 <strong>${rows.filter(c=>c.aReview.exact_request_satisfied).length}/10</strong></span><span>额外变体 <strong>${review.additional_regression.passed}/${review.additional_regression.total}</strong></span></div>
<div class="notice"><strong>100分是这10题的回归评分，不是100%能力覆盖。</strong><br>RC-09、RC-10按原标准考察“诚实的替代方案”，本次仍没有实现自动重复小时窗口、自动跨月账期和节假日筛选。两题明确标记原需求未完整满足。${esc(review.scope)}</div>
<h2>主要变化</h2><ol class="priorities"><li><b>原节奏保留下来了。</b>14:00开始每20分钟，在14:37:20生成草稿，下一候选是14:40，不再从现在重新计时。</li><li><b>跨月日期完整了。</b>25日至次月5日保留为完整单轮；说明不会自动产生下一账期，不能自动筛选节假日。</li><li><b>时间说明与最终规则一致。</b>AI标记补全项与能力限制，后端计算最终时间和完成说明，避免复核改了规则、说明仍引用旧候选。</li><li><b>补全项与边界更清楚。</b>模糊时间明确可调整；纽约时区原样保存；备注不再承诺未实现的自动循环。</li></ol>
<h2>速度与代价</h2><div class="statline"><span>固定时间组中位数 <strong>${sec(oldLatency)} → ${sec(latency)}</strong></span><span>10题模型请求 <strong>${oldCalls} → ${calls} 次</strong></span></div><p class="muted">初稿合法时增加一次语义复核；初稿非法时使用一次修复，两次共用65秒期限。两批采样网络状态不同，耗时仅为观察值，不是性能保证。公开接口协议保持不变。</p>
<h2>10题结果</h2><div class="table-scroll"><table class="overview"><thead><tr><th>题号</th><th>考察点</th><th>前 → 后</th><th>本次结论</th><th>后端路由耗时</th></tr></thead><tbody>${comparison}</tbody></table></div>
<h2>逐题查看生成效果</h2><div class="toolbar"><button id="expand" type="button">展开题目</button><button id="collapse" type="button">收起题目</button></div>${details}
<section class="method"><h2>证据与验证边界</h2><ul>
<li><strong>本轮后端实测：</strong>真实 /api/v1/ai/parse Handler + ${esc(models.join('、'))}，每题注入原固定时间，独立临时数据库和测试邮件器；10题均连续保存原始输出、模型调用和候选预览。开始 ${esc(current.started_at)}，结束 ${esc(current.finished_at)}。</li>
<li><strong>评分口径：</strong>${esc(review.review_method)} RC-09/10的边界通过不等于完整支持原需求。</li>
<li><strong>调优过程：</strong>${esc(review.development_history)}</li>
<li><strong>额外回归：</strong>${esc(review.additional_regression.cases.join('；'))}。${esc(review.additional_regression.method)}</li>
<li><strong>运行进程验证边界：</strong>${esc(review.binary_provenance.note)} 上一轮已有后端HTTP测试地址是 ${esc(oldHTTP.endpoint)}，其结果保留在旧报告，不当作新版实测。</li>
<li><strong>无业务副作用：</strong>本轮各题任务、通知、完成、提醒计划、花园奖励均为0，测试邮件为0。未确认保存任务、未启动调度器，没有验证手机通知、闹钟、日历和邮件送达。候选时间不等于实际送达承诺。</li>
<li><strong>源码与检查：</strong>${esc(review.source_commit)}；Go全量测试、静态检查通过，复核取消时不返回初稿。Windows和Linux amd64已构建；这不代表Linux运行或部署验收。</li></ul>
<div class="downloads"><a href="../2026-10-08/index.html">查看优化前完整报告</a><a href="fixed-results.json" download>本轮原始返回</a><a href="review.json" download>评分依据</a><a href="../../reminder-creation-v1.json" download>原始10题</a></div></section>
<footer>离线报告，不加载外部脚本或统计服务。两次单轮采样与定向回归不能推算长期稳定性；确认后的任务执行链仍需实际使用验证。</footer></main><script>${script}</script></body></html>`;
fs.writeFileSync(path.join(run,'index.html'),html.replace(/[ \t]+$/gm,'')+'\n');
const manifest={generated_at:new Date().toISOString(),source_commit:review.source_commit,before_score:oldTotal,after_score:total,exact_request_satisfied:rows.filter(c=>c.aReview.exact_request_satisfied).length,handler_success:success,old_median_ms:oldLatency,new_median_ms:latency,old_model_requests:oldCalls,new_model_requests:calls,suite_sha256:hash(suitePath),files:{},baseline_files:{}};
for(const f of ['fixed-results.json','review.json','index.html'])manifest.files[f]=hash(path.join(run,f));
for(const f of ['fixed-results.json','review.json','http-results.json','index.html'])manifest.baseline_files[f]=hash(path.join(baseline,f));
fs.writeFileSync(path.join(run,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify(manifest,null,2));
