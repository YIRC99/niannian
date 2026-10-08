/* All interactions are local illustrations. No accounts, network requests or storage. */
(() => {
  'use strict';
  const examples = {
    laundry: { task: '收衣服', time: '15 分钟后', reason: '先留一点时间做完手头的事，再提醒你去阳台看看。', repeat: '未完成时，每 5 分钟提醒', end: '这件事就结束，不再催办' },
    reading: { task: '读一会儿书', time: '每天 20:30', reason: '给晚上的自己留一小段阅读时间。不用一口气读完，翻几页也很好。', repeat: '每天晚上提醒，按天分别完成', end: '今天做完就停，明天继续' },
    monthly: { task: '完成本月申报', time: '每月 1–15 日 · 09:00', reason: '在申报窗口内持续记着这件事，本月完成后，就可以安心等下个月。', repeat: '每月 1–15 日，每天上午提醒', end: '本月完成就停，下月重新开始' }
  };
  const fields = { task: 'draft-task', time: 'draft-time', reason: 'draft-reason', repeat: 'draft-repeat', end: 'draft-end' };
  const scenarioButtons = document.querySelectorAll('[data-scenario]');
  scenarioButtons.forEach(button => button.addEventListener('click', () => {
    const example = examples[button.dataset.scenario];
    if (!example) return;
    scenarioButtons.forEach(item => {
      const selected = item === button;
      item.classList.toggle('active', selected);
      item.setAttribute('aria-pressed', String(selected));
    });
    Object.entries(fields).forEach(([field, id]) => { document.getElementById(id).textContent = example[field]; });
  }));

  const flowers = { daisy: '雏菊', tulip: '郁金香', hydrangea: '绣球', sunflower: '向日葵' };
  let growth = 0;
  let flower = 'daisy';
  const plant = document.getElementById('demo-plant');
  const waterButton = document.getElementById('water-button');
  const status = document.getElementById('growth-status');
  const flowerButtons = document.querySelectorAll('[data-flower]');
  function showGarden(animate = false) {
    const stage = growth >= 7 ? '开花了' : growth >= 3 ? '长出叶子' : growth >= 1 ? '发芽了' : '等待发芽';
    const asset = growth >= 7 ? flower : growth >= 3 ? 'leaf' : growth >= 1 ? 'sprout' : 'seed';
    plant.src = `assets/${asset}.webp`;
    plant.alt = `${flowers[flower]}，${stage}`;
    document.getElementById('plant-name').textContent = `${flowers[flower]} · ${stage}`;
    document.getElementById('growth-count').textContent = `成长 ${growth} / 7`;
    waterButton.disabled = growth >= 7;
    waterButton.textContent = growth >= 7 ? '已经开花啦 ✓' : '浇一滴水 ＋';
    status.textContent = growth >= 7 ? '你的小花开了。每一点照顾，都有回音。' : growth >= 3 ? '叶子长出来了，再给它一点时间。' : growth >= 1 ? '小芽探出头了，慢慢来就很好。' : '浇下第一滴水，看看会发生什么。';
    if (animate) {
      plant.classList.remove('growing');
      requestAnimationFrame(() => { requestAnimationFrame(() => plant.classList.add('growing')); });
    }
  }
  plant.addEventListener('animationend', () => plant.classList.remove('growing'));
  waterButton.addEventListener('click', () => { if (growth < 7) { growth += 1; showGarden(true); } });
  document.getElementById('reset-garden').addEventListener('click', () => { growth = 0; showGarden(); });
  flowerButtons.forEach(button => button.addEventListener('click', () => {
    if (!flowers[button.dataset.flower]) return;
    flower = button.dataset.flower;
    flowerButtons.forEach(item => { const selected = item === button; item.classList.toggle('selected', selected); item.setAttribute('aria-pressed', String(selected)); });
    showGarden(true);
  }));
})();
