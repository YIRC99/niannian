// Offline design samples only. No SMTP calls or application configuration changes.
import { mkdirSync, copyFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const assets = resolve(here, '../../..', 'android/app/src/main/res/drawable-nodpi');
mkdirSync(join(here, 'assets'), { recursive: true });
for (const [from, to] of [
  ['sowing_seed_mascot.png', 'seed.png'],
  ['calendar_mail.png', 'letter.png'],
  ['sowing_footer_foliage.png', 'garden.png'],
]) copyFileSync(join(assets, from), join(here, 'assets', to));

const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const font = "-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',Arial,sans-serif";
const row = (html, style = '') => `<tr><td style="${style}">${html}</td></tr>`;
const table = (html, style = '') => `<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="border-collapse:separate;${style}">${html}</table>`;
const text = (s, style = '') => `<p style="margin:0;font-size:15px;line-height:1.85;${style}">${esc(s)}</p>`;
const img = (name, width) => `<img src="assets/${name}.png" width="${width}" alt="" style="display:block;width:${width}px;max-width:100%;height:auto;border:0;margin:0 auto;">`;
const brand = (color = '#527A58') => `<span style="font-size:22px;font-weight:750;letter-spacing:3px;color:${color}">念念</span>`;
const code = (bg, color) => text('482619', `font-family:Consolas,monospace;font-size:36px;letter-spacing:7px;font-weight:700;text-align:center;background:${bg};color:${color};padding:19px 8px;border-radius:12px;`);
const sample = {
  title: '给自己留 20 分钟读书',
  body: '把手机放在一旁，从昨天停下的那一页开始。读几页也很好，慢慢来。',
};
const account = '可用于注册、设置或重设密码及验证码登录，10 分钟内有效。';
const safety = '请勿把验证码分享给他人。若非本人操作，请忽略本邮件。';
const action = '做完后，打开「念念」点击完成。';
const notice = otp => otp ? '这是一封账号验证邮件，请勿直接回复。' : '你在念念中开启了邮件提醒。可在 App 的提醒设置中调整。';

function shell(title, preview, content, bg) {
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>${esc(title)}</title>
<style>body{margin:0;padding:0}table{mso-table-lspace:0;mso-table-rspace:0}td{overflow-wrap:anywhere;word-break:break-word}img{border:0} @media(max-width:480px){.outer{padding:14px 10px!important}.pad{padding-left:24px!important;padding-right:24px!important}.headline{font-size:25px!important}}</style></head>
<body style="margin:0;background:${bg};font-family:${font};color:#283C32;">
<div style="display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;">${esc(preview)}</div>
${table(row(`<!--[if mso]><table role="presentation" width="560" align="center"><tr><td><![endif]-->${table(content, 'max-width:560px;margin:0 auto;')}<!--[if mso]></td></tr></table><![endif]-->`, 'padding:28px 16px;'), `background:${bg};`)}
</body></html>`;
}

function garden(otp) {
  const title = otp ? '你的验证码，送到啦' : '有件小事，到时间啦';
  const inner = table(
    row(table(`<tr><td>${brand()}</td><td align="right" style="font-size:12px;color:#68786D;">${otp ? '账号验证' : '小事提醒'}</td></tr>`), 'padding:26px 30px 4px;') +
    row(img('letter', 184), 'padding:20px 30px 0;') +
    row(`<h1 class="headline" style="font-size:28px;line-height:1.4;margin:12px 0 10px;text-align:center;color:#284D36;">${title}</h1>${text(otp ? '回到念念，填入下面 6 位数字就好。' : '念念帮你记着，你慢慢把它做好。', 'text-align:center;color:#68786D;font-size:14px;')}`, 'padding:0 24px 26px;') +
    row(otp ? code('#EDF3E6', '#355D3E') : table(
      row(text('这次要做的小事', 'font-size:12px;color:#607459;') + `<h2 style="font-size:22px;line-height:1.5;margin:9px 0 12px;">${esc(sample.title)}</h2>` + text(sample.body, 'color:#52644F;'), 'padding:22px 24px;'), 'background:#EDF3E6;border-radius:16px;'), 'padding:0 24px;') +
    row(text(otp ? account : action, 'font-weight:600;') + text(otp ? safety : '也可以留下一句话或一张照片。', 'color:#68786D;font-size:13px;margin-top:5px;'), 'padding:24px 28px 8px;') +
    row(text(otp ? '安心使用，慢慢把生活照顾好。' : '把小事放在心上，也把自己照顾好。', 'font-size:12px;text-align:center;color:#68786D;'), 'padding:18px 22px 0;') +
    row(img('garden', 560), 'padding:0;'), 'background:#FFFDF7;border:1px solid #DEE5D5;border-radius:22px;overflow:hidden;');
  return shell(title, otp ? '你的念念验证码，10 分钟内有效。' : sample.title, row(inner) + row(text(notice(otp), 'font-size:11px;color:#68786D;text-align:center;'), 'padding:18px 16px 0;'), '#F2F5EC');
}

function letter(otp) {
  const inner = table(
    row(table(`<tr><td>${brand('#736047')}</td><td align="right" style="font-size:11px;letter-spacing:2px;color:#897A67;">一封${otp ? '安心' : '小事'}来信</td></tr>`), 'padding:30px 30px 24px;border-bottom:1px solid #E8E0D2;') +
    row(img('letter', 145), 'padding:26px 30px 10px;') +
    row(text('嗨，见字好。', 'font-size:17px;color:#736047;') + `<h1 class="headline" style="font-size:29px;line-height:1.55;font-weight:600;margin:16px 0 18px;color:#4E493B;">${otp ? '这是你的<br>念念验证码。' : '你想做的那件小事，<br>念念还记得。'}</h1>` + (otp ? text('请回到念念，输入这 6 位数字。', 'color:#746F62;') : text(sample.title, 'font-size:20px;font-weight:700;color:#5C684D;')), 'padding:0 32px;') +
    row(otp ? code('#F3EEE3', '#655A45') : text(sample.body, 'color:#746F62;font-size:16px;line-height:2;'), 'padding:22px 32px;') +
    row(text(otp ? account : action, 'color:#5D594D;') + text(otp ? safety : '想记下今天的感受，也可以附上文字或照片。', 'font-size:13px;color:#82796B;margin-top:6px;'), 'padding:20px 32px 0;border-top:1px solid #E8E0D2;') +
    row(text('陪你把日子过好的', 'font-size:12px;color:#82796B;text-align:right;') + text('念念', 'font-size:21px;color:#736047;text-align:right;margin-top:4px;'), 'padding:26px 32px 30px;'), 'background:#FFFCF5;border:1px solid #E7DECE;border-radius:4px;');
  return shell(otp ? '念念 · 账号验证码' : '念念给你的一封小事来信', otp ? '你的念念验证码，10 分钟内有效。' : sample.title, row(inner) + row(text(notice(otp), 'font-size:11px;color:#82796B;text-align:center;'), 'padding:18px 16px 0;'), '#F1ECE2');
}

function clean(otp) {
  const inner = table(
    row(table(`<tr><td>${brand('#F8FAF5')}</td><td align="right" style="font-size:12px;color:#DAE5D7;">${otp ? '账号验证' : '生活提醒'}</td></tr>`), 'padding:25px 28px;background:#375943;border-radius:16px 16px 0 0;') +
    row(text(otp ? '验证你的邮箱' : '现在，做一件小事', 'font-size:12px;color:#68786D;letter-spacing:1px;') + `<h1 class="headline" style="font-size:28px;line-height:1.5;margin:14px 0 0;">${otp ? '你的账号验证码' : esc(sample.title)}</h1>`, 'padding:32px 28px 20px;') +
    row(otp ? code('#EEF3ED', '#375943') : text(sample.body, 'font-size:16px;color:#59665C;line-height:1.9;'), 'padding:0 28px 28px;') +
    row(text(otp ? '10 分钟内有效' : '完成之后', 'font-size:12px;color:#68786D;') + text(otp ? '回到念念，输入上面的验证码。' : action, 'font-weight:600;margin-top:7px;') + text(otp ? '可用于注册、设置或重设密码及验证码登录。' : '可附上文字或照片，留住这次完成。', 'font-size:13px;color:#68786D;margin-top:5px;'), 'padding:22px 28px;background:#F3F6F0;') +
    row(text(otp ? safety : '从一件小事开始，生活会慢慢有条理。', 'font-size:12px;color:#68786D;'), 'padding:24px 28px;'), 'background:#FDFEF9;border:1px solid #DAE3D8;border-radius:16px;');
  return shell(otp ? '念念 · 账号验证码' : '念念提醒 · ' + sample.title, otp ? '你的念念验证码，10 分钟内有效。' : sample.title, row(inner) + row(text(notice(otp), 'font-size:11px;color:#68786D;text-align:center;'), 'padding:18px 16px 0;'), '#F0F3EF');
}

const variants = [
  { id: 'a-garden', name: '花园来信', tag: '已选定', desc: 'A 的花园排版，配上 B 的信封插画。', render: garden },
  { id: 'b-letter', name: '温柔来信', tag: '更温柔', desc: '像收到一封短信，留白多，语气轻。', render: letter },
  { id: 'c-clean', name: '清爽提醒', tag: '更利落', desc: '没有插画，打开就看到该做什么。', render: clean },
];
const documents = {};
for (const v of variants) {
  documents[v.id] = {};
  for (const kind of ['reminder', 'code']) {
    const html = v.render(kind === 'code');
    documents[v.id][kind] = html;
    writeFileSync(join(here, `${v.id}-${kind}.html`), html);
  }
}

const cards = variants.map((v, i) => `<section class="variant" data-id="${v.id}"><header class="variant-head"><div><span class="letter">${String.fromCharCode(65+i)}</span><h2>${v.name}</h2><span class="tag">${v.tag}</span></div><p>${v.desc}</p></header><div class="mail-head"><span>念念提醒</span><span class="subject">给自己留 20 分钟读书</span></div><iframe title="${v.name}邮件预览" sandbox="allow-same-origin" src="${v.id}-reminder.html"></iframe><a class="open" href="${v.id}-reminder.html" target="_blank" rel="noopener">单独打开 ${String.fromCharCode(65+i)} →</a></section>`).join('');

writeFileSync(join(here, 'index.html'), `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>念念 · 选一封喜欢的邮件</title><style>
*{box-sizing:border-box}body{margin:0;background:#F7F8F2;color:#283C32;font-family:${font}}main{max-width:1440px;margin:auto;padding:44px 32px 26px}.eyebrow{font-size:13px;color:#657C63;letter-spacing:3px;margin:0 0 12px}h1{font-size:34px;letter-spacing:-1px;margin:0 0 12px}.intro{font-size:14px;color:#68786D;margin:0;line-height:1.8}.toolbar{display:flex;align-items:center;gap:18px;flex-wrap:wrap;margin:26px 0 28px}.tabs{display:flex;gap:4px;background:#EAEFE3;padding:4px;border-radius:12px}button{font:inherit;font-size:13px;border:0;border-radius:8px;background:transparent;color:#556551;padding:10px 17px;cursor:pointer}button[aria-pressed="true"]{background:#FFFDF7;color:#284D36;box-shadow:0 2px 5px #283C3210}button:focus-visible,a:focus-visible{outline:3px solid #527A58;outline-offset:3px}label{font-size:13px;color:#63715E;display:flex;gap:7px;align-items:center}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}.variant{min-width:0}.variant-head>div{display:flex;align-items:center;gap:10px}.letter{font-family:Georgia,serif;font-size:27px;color:#547752}h2{font-size:17px;margin:0}.tag{font-size:11px;background:#E7EFDF;padding:4px 8px;border-radius:5px;color:#4D6847}.variant-head p{font-size:12px;line-height:1.7;color:#68786D;margin:9px 0 18px}.mail-head{border:1px solid #DFE5D7;border-bottom:0;border-radius:13px 13px 0 0;background:#FFFDF7;padding:14px 18px;display:flex;flex-direction:column;gap:5px;font-size:11px;color:#68786D}.subject{font-size:12px;color:#283C32}iframe{display:block;width:100%;height:820px;border:1px solid #DFE5D7;background:#F2F5EC;border-radius:0 0 13px 13px}.open{display:inline-block;font-size:12px;color:#527A58;text-underline-offset:4px;margin:15px 0 0}.foot{border-top:1px solid #DFE5D7;margin-top:28px;padding-top:18px;font-size:12px;line-height:1.9;color:#68786D}.wide .grid{grid-template-columns:1fr}.wide .variant{width:100%;max-width:650px;margin:0 auto 20px}.wide iframe{height:850px}@media(max-width:960px){.grid{grid-template-columns:1fr}.variant{width:100%;max-width:440px;margin:0 auto 22px}main{padding:28px 18px}h1{font-size:27px}iframe{height:830px}.toolbar{gap:12px}}
</style></head><body><main><p class="eyebrow">念念 / 邮件样式提案</p><h1>让每一次提醒，也好看一点。</h1><p class="intro">三种风格，同一件小事。选你最喜欢的那一封。</p><div class="toolbar"><div class="tabs" aria-label="邮件类型"><button data-kind="reminder" aria-pressed="true">事项提醒</button><button data-kind="code" aria-pressed="false">账号验证码</button></div><div class="tabs" aria-label="预览宽度"><button data-width="mobile" aria-pressed="true">并排比较</button><button data-width="desktop" aria-pressed="false">桌面宽度</button></div><label><input type="checkbox" id="hide-images">看看不显示图片的效果</label></div><div class="grid">${cards}</div><p class="foot">这里是示例邮件，任务和验证码均为演示内容。邮件内的完成提示需要回到 App 操作。<br>A 已选定并接入发送代码；此页仅作预览，邮箱效果以更新后端后的实际收信为准。</p></main><script>
const documents = ${JSON.stringify(documents).replace(/</g, '\\u003c')};
let kind = 'reminder';
function refresh(){document.querySelectorAll('.variant').forEach(v=>{let html=documents[v.dataset.id][kind];if(document.getElementById('hide-images').checked)html=html.replace('</head>','<style>img{display:none!important}</style></head>');v.querySelector('iframe').srcdoc=html;v.querySelector('.open').href=v.dataset.id+'-'+kind+'.html';v.querySelector('.subject').textContent=kind==='code'?'念念 · 账号验证码':'给自己留 20 分钟读书';});}
document.querySelectorAll('[data-kind]').forEach(b=>b.addEventListener('click',()=>{kind=b.dataset.kind;document.querySelectorAll('[data-kind]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));refresh();}));
document.querySelectorAll('[data-width]').forEach(b=>b.addEventListener('click',()=>{document.body.classList.toggle('wide',b.dataset.width==='desktop');document.querySelectorAll('[data-width]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));}));
document.getElementById('hide-images').addEventListener('change',refresh);refresh();
</script></body></html>`);
console.log('Generated 3 styles × 2 email types and offline comparison page.');
