import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1500,height:1000});
const ordem=[]; const errors=[];
page.on('request', r => {
  if (r.method()!=='POST') return;
  const u=r.url();
  if (u.includes('/versioning/commits')) ordem.push('GRAVOU VERSAO');
  else if (u.endsWith('/reviews')) ordem.push('SUBMETEU REVISAO');
});
page.on('pageerror', e=>errors.push(e.message.slice(0,140)));
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t'); localStorage.setItem('lab_dms_lang','pt');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'a@a',email:'a@a',fullName:'A',roles:['ROLE_ADMIN','DOCUMENT_CREATE','DOCUMENT_EDIT']}));
});
await page.goto('http://127.0.0.1:8100/dashboard/documents/doc-1/edit',{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,4000));

// escrever no editor para o deixar "sujo"
await page.click('.cm-content');
await page.keyboard.type('\n% alteracao por gravar\n');
await new Promise(r=>setTimeout(r,800));
const sujo = await page.evaluate(()=> /\% alteracao por gravar/.test(document.querySelector('.cm-content')?.innerText||''));
console.log('  editor com alteracao nao gravada:', sujo);

// abrir o modal de submeter
await page.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/submeter para aprova/i.test(b.textContent||''))?.click());
await new Promise(r=>setTimeout(r,1000));
// preencher o titulo do pedido
await page.evaluate(() => {
  const set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  const i=[...document.querySelectorAll('input[type=text]')].find(x=>!x.readOnly);
  if(i){ set.call(i,'Pedido de aprovacao'); i.dispatchEvent(new Event('input',{bubbles:true})); }
});
await new Promise(r=>setTimeout(r,400));
await page.evaluate(() => {
  const bs=[...document.querySelectorAll('button')].filter(b=>/submeter para aprova/i.test(b.textContent||''));
  bs[bs.length-1]?.click();
});
await new Promise(r=>setTimeout(r,3000));

console.log('  ordem observada:', JSON.stringify(ordem));
const ok = ordem[0]==='GRAVOU VERSAO' && ordem.includes('SUBMETEU REVISAO');
console.log('  VEREDITO:', ok ? 'gravou a versao ANTES de submeter — correto'
                              : 'NAO gravou antes de submeter — ERRADO');
console.log('  ERROS', JSON.stringify(errors.slice(0,3)));
await browser.close();
