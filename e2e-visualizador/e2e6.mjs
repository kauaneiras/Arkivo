import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1500,height:1000});
const errors=[]; page.on('pageerror',e=>errors.push(e.message.slice(0,140)));
const dels=[]; page.on('response', async r => { if (r.request().method()==='DELETE') dels.push(r.status()+' '+r.url().split('/').pop()); });
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t'); localStorage.setItem('lab_dms_lang','pt');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'admin@email.com',email:'admin@email.com',fullName:'Administrador',roles:['ROLE_ADMIN','DOCUMENT_CREATE']}));
});
await page.goto('http://127.0.0.1:8101/dashboard/users',{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,3000));

const linhas = await page.evaluate(() => {
  const trs=[...document.querySelectorAll('tr')].filter(tr=>/@email\.com/.test(tr.innerText));
  return trs.map(tr => ({
    quem: (tr.innerText.split('\n')[0]||'').trim(),
    estado: /Desativado/.test(tr.innerText) ? 'Desativado' : (/Ativo/.test(tr.innerText)?'Ativo':'?'),
    acoes: [...tr.querySelectorAll('button')].map(b=>(b.textContent||b.getAttribute('title')||'').trim()).filter(x=>/Desativar|Reativar|Apagar/.test(x)),
    ehVoce: /esta é você/i.test(tr.innerText),
    esmaecida: /opacity-60/.test(tr.className),
  }));
});
console.log('LINHAS'); linhas.forEach(l=>console.log('  ', JSON.stringify(l)));

// tentar apagar o Escritor (tem historico -> deve recusar com explicacao)
await page.evaluate(() => {
  const tr=[...document.querySelectorAll('tr')].find(t=>/writer@email\.com/.test(t.innerText));
  [...tr.querySelectorAll('button')].find(b=>/Apagar/.test(b.getAttribute('title')||''))?.click();
});
await new Promise(r=>setTimeout(r,700));
const confirmacao = await page.evaluate(()=> (document.body.innerText.match(/Apagar a conta\?[\s\S]{0,200}/)||[null])[0]);
console.log('CONFIRMACAO:', JSON.stringify((confirmacao||'').replace(/\n+/g,' | ').slice(0,170)));
await page.evaluate(() => {
  const btns=[...document.querySelectorAll('button')].filter(b=>/^(Remover|Apagar|Excluir|Delete)$/i.test((b.textContent||'').trim()));
  btns[btns.length-1]?.click();
});
await new Promise(r=>setTimeout(r,2000));
const aviso = await page.evaluate(()=> (document.body.innerText.match(/Esta conta tem[^\n]*/)||[null])[0]);
console.log('RECUSA_EXPLICATIVA:', JSON.stringify((aviso||'NENHUMA').slice(0,150)));
console.log('DELETES_ENVIADOS', JSON.stringify(dels));
console.log('ERROS', JSON.stringify(errors.slice(0,3)));
await browser.close();
