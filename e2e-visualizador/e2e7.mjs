import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1400,height:1000});
const errors=[]; page.on('pageerror',e=>errors.push(e.message.slice(0,140)));
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t'); localStorage.setItem('lab_dms_lang','pt');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'a@a',email:'a@a',fullName:'A',roles:['ROLE_ADMIN','DOCUMENT_CREATE']}));
});
await page.goto('http://127.0.0.1:8101/dashboard/documents',{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,3000));
await page.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/novo documento/i.test(b.textContent||''))?.click());
await new Promise(r=>setTimeout(r,1200));

const medir = () => page.evaluate(() => {
  const rot=[...document.querySelectorAll('label')].find(l=>/quem pode ver|visibilidade|privacidade/i.test(l.textContent||''));
  const btnRestrito=[...document.querySelectorAll('button')].find(b=>/^restrito$/i.test((b.textContent||'').trim()));
  const sel=[...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>/escolha um setor/i.test(o.textContent)));
  const y=el=>el?Math.round(el.getBoundingClientRect().top):null;
  const dentroDaVista = el => { if(!el) return null; const r=el.getBoundingClientRect();
    const sc=el.closest('form')?.parentElement; const cr=sc?sc.getBoundingClientRect():{top:0,bottom:innerHeight};
    return r.top>=cr.top-2 && r.bottom<=cr.bottom+2; };
  return { yVisibilidade:y(rot), yBotaoRestrito:y(btnRestrito), ySetor:y(sel),
           setorVisivel: !!sel, setorNaVista: dentroDaVista(sel) };
});

console.log('ANTES de escolher Restrito:', JSON.stringify(await medir()));
await page.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/^restrito$/i.test((b.textContent||'').trim()))?.click());
await new Promise(r=>setTimeout(r,700));
const d = await medir();
console.log('DEPOIS:', JSON.stringify(d));
console.log('VEREDITO:', d.ySetor > d.yBotaoRestrito
  ? `setor ${d.ySetor - d.yBotaoRestrito}px ABAIXO do botao Restrito — correto`
  : `setor ${d.yBotaoRestrito - d.ySetor}px ACIMA — ERRADO`);
console.log('setor visivel sem rolar:', d.setorNaVista);
console.log('ERROS', JSON.stringify(errors.slice(0,3)));
await browser.close();
