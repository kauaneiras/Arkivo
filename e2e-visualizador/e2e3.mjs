import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';

const BASE='http://127.0.0.1:8101';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1400,height:900});
const errors=[];
page.on('pageerror', e => errors.push(e.message.slice(0,140)));
page.on('console', m => { if (m.type()==='error') errors.push(m.text().slice(0,140)); });

await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'t@t',email:'t@t',fullName:'Teste',roles:[]}));
});
await page.goto(`${BASE}/dashboard/documents`, {waitUntil:'networkidle0', timeout:60000});
await new Promise(r=>setTimeout(r,3500));

// --- item 5: o botao de criar ---
const btn = await page.evaluate(() => {
  const b = [...document.querySelectorAll('button')].find(x => /novo|new|nueva|nouveau|neu|新建/i.test(x.textContent||'') && x.querySelector('svg'));
  if (!b) return { achado:false };
  return { achado:true, texto:b.textContent.trim(), desativado:b.disabled,
           dica:b.getAttribute('title'), classe:/cursor-not-allowed/.test(b.className) };
});
console.log('BOTAO_CRIAR', JSON.stringify(btn));

// --- item 6: quantos idiomas oferece ---
const langs = await page.evaluate(() => {
  const s = document.querySelector('#lang-select');
  return s ? [...s.options].map(o => `${o.value}:${o.textContent}`) : null;
});
console.log('IDIOMAS', JSON.stringify(langs));

// --- trocar para chines e ver se a interface muda + se grava no servidor ---
if (langs) {
  await page.select('#lang-select', 'zh');
  await new Promise(r=>setTimeout(r,1500));
  const zh = await page.evaluate(() => document.body.innerText.slice(0,160).replace(/\n+/g,' | '));
  console.log('APOS_ZH', JSON.stringify(zh));
}
console.log('ERROS', JSON.stringify(errors.slice(0,4)));
await browser.close();
