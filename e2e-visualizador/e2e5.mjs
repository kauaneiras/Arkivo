import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1400,height:1000});
const posts=[];
page.on('request', r => { if (r.method()==='POST' && r.url().includes('/api/v1/documents')) posts.push(r.url()); });
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t'); localStorage.setItem('lab_dms_lang','pt');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'t@t',email:'t@t',fullName:'T',roles:['ROLE_ADMIN','DOCUMENT_CREATE']}));
});
await page.goto('http://127.0.0.1:8101/dashboard/documents',{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,3000));
await page.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/novo documento/i.test(b.textContent||''))?.click());
await new Promise(r=>setTimeout(r,1200));
// preencher titulo e marcar Restrito
await page.evaluate(() => {
  const set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  const inputs=[...document.querySelectorAll('input[type=text]')];
  const titulo = inputs.find(i => !i.readOnly);
  if (titulo){ set.call(titulo,'Doc de teste'); titulo.dispatchEvent(new Event('input',{bubbles:true})); }
  [...document.querySelectorAll('button')].find(b=>/^restrito$/i.test((b.textContent||'').trim()))?.click();
});
await new Promise(r=>setTimeout(r,700));

const antes = await page.evaluate(() => {
  const form = document.querySelector('#create-doc-form');
  const sel = [...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>/escolha um setor/i.test(o.textContent)));
  const btn = [...document.querySelectorAll('button')].find(b=>/^criar/i.test((b.textContent||'').trim()));
  return {
    formValido: form ? form.checkValidity() : null,
    selectValido: sel ? sel.checkValidity() : null,
    mensagemNativa: sel ? sel.validationMessage : null,
    botao: btn ? { texto:btn.textContent.trim(), type:btn.type, form:btn.getAttribute('form') } : null,
  };
});
console.log('ANTES_DE_SUBMETER', JSON.stringify(antes, null, 1));

await page.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/^criar/i.test((b.textContent||'').trim()))?.click());
await new Promise(r=>setTimeout(r,1500));
const depois = await page.evaluate(() => ({
  erroNaTela: (document.body.innerText.match(/Escolha o setor[^\n]*/)||[null])[0],
  modalAindaAberto: !!document.querySelector('#create-doc-form'),
}));
console.log('DEPOIS', JSON.stringify(depois));
console.log('POSTS_ENVIADOS', JSON.stringify(posts), '(esperado: [] — nada foi criado)');
await browser.close();
