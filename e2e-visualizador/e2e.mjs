import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';

const BASE='http://127.0.0.1:8100';
const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1400,height:900});
const errors=[];
page.on('console',m=>{if(m.type()==='error')errors.push(m.text().slice(0,160));});
page.on('pageerror',e=>errors.push('PAGEERROR: '+e.message.slice(0,160)));
await page.evaluateOnNewDocument(()=>{
  localStorage.setItem('jwt_token','fake.jwt.token');
  localStorage.setItem('user_info',JSON.stringify({id:'u1',username:'admin@email.com',
    email:'admin@email.com',fullName:'Administrador',roles:['ROLE_ADMIN']}));
});
await page.goto(`${BASE}/dashboard/documents/doc-1/edit`,{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,7000));

const r = await page.evaluate(()=>{
  // o painel de pre-visualizacao e o ultimo bloco da direita
  const painel = [...document.querySelectorAll('div')]
    .find(d => /PRÉ-VISUALIZAÇÃO|PDF PREVIEW/i.test(d.textContent||'') && d.clientWidth>300 && d.clientWidth<800);
  const canvases=[...document.querySelectorAll('canvas')].map(c=>{
    try{const d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;
      for(let i=0;i<d.length;i+=4) if(d[i]<250||d[i+1]<250||d[i+2]<250)n++;
      return {w:c.width,h:c.height,pixels:n};}catch(e){return{erro:e.message};}
  });
  const obj=document.querySelector('object[type="application/pdf"]');
  return {
    modoInicial: canvases.length ? 'paginado' : (obj ? 'leitor do navegador' : 'nenhum'),
    canvases,
    temObject: !!obj,
    caixaDeErro: painel ? (painel.textContent.match(/(não abriu|did not open)[\s\S]{0,160}/i)||[null])[0] : null,
    ofereceVoltar: !!([...document.querySelectorAll('button')].find(b=>/leitor do navegador|browser reader/i.test(b.textContent||''))),
  };
});
fs.mkdirSync('shots',{recursive:true});
await page.screenshot({path:process.env.SHOT||'shots/x.png'});
console.log(JSON.stringify(r,null,1));
console.log('erros de consola:', errors.length?errors.join(' | '):'nenhum');
await browser.close();
