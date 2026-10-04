// Run with Node and the bundled workspace dependencies in NODE_PATH.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1080, height: 1440 }, deviceScaleFactor: 1 });
    await page.goto(pathToFileURL(path.join(__dirname, 'posters.html')).href);
    await page.evaluate(async () => {
      document.body.classList.add('exporting');
      await document.fonts.ready;
      await Promise.all([...document.images].map(img => img.decode()));
    });
    fs.mkdirSync(path.join(__dirname, 'images'), { recursive: true });
    const pages = await page.locator('.poster').all();
    for (const poster of pages) {
      const name = await poster.getAttribute('data-name');
      await poster.screenshot({ path: path.join(__dirname, 'images', name + '.png'), animations: 'disabled' });
      console.log(name + '.png');
    }
    const manifest = { width:1080, height:1440, count:pages.length, files:fs.readdirSync(path.join(__dirname,'images')).filter(f=>f.endsWith('.png')) };
    console.log(JSON.stringify(manifest));
    await page.setViewportSize({width:1440,height:1200});
    await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(img=>img.decode()));});
    await page.screenshot({path:path.join(__dirname,'00-整套预览.png'),fullPage:true,animations:'disabled'});
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
