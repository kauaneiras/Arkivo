import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';

const browser = await puppeteer.launch({
  args:[...chromium.args,'--no-sandbox','--disable-setuid-sandbox'],
  executablePath: await chromium.executablePath(), headless:'shell' });
const page = await browser.newPage();
await page.setViewport({width:1400,height:1000});
const errors=[];
page.on('pageerror', e=>errors.push(e.message.slice(0,140)));
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('jwt_token','t');
  localStorage.setItem('lab_dms_lang','pt');
  localStorage.setItem('user_info', JSON.stringify({id:'u1',username:'t@t',email:'t@t',fullName:'T',roles:['ROLE_ADMIN','DOCUMENT_CREATE']}));
});
await page.goto('http://127.0.0.1:8101/dashboard/documents',{waitUntil:'networkidle0',timeout:60000});
await new Promise(r=>setTimeout(r,3000));

// abrir o modal
await page.evaluate(() => {
  [...document.querySelectorAll('button')].find(b=>/novo documento/i.test(b.textContent||''))?.click();
});
await new Promise(r=>setTimeout(r,1500));

const snap = async () => page.evaluate(() => {
  const txt = document.body.innerText;
  const selects = [...document.querySelectorAll('select')];
  const grupo = selects.find(s => [...s.options].some(o => /soldagem|escolha um setor/i.test(o.textContent)));
  const checks = [...document.querySelectorAll('input[type=checkbox]')].map(c => ({
    label: c.closest('label')?.innerText?.trim().slice(0,40), marcado: c.checked }));
  const numeros = [...document.querySelectorAll('input[type=number]')].map(n => ({
    valor: n.value, desativado: n.disabled }));
  return {
    temCampoGrupo: !!grupo,
    grupoObrigatorio: grupo ? grupo.required : null,
    opcoesGrupo: grupo ? [...grupo.options].map(o=>o.textContent) : null,
    avisoCodigoAuto: /Gerado automaticamente pela categoria/i.test(txt),
    temDefinirManualmente: /Definir manualmente/i.test(txt),
    checkboxes: checks, numeros,
  };
});

console.log('=== 3. codigo automatico ===');
let s = await snap();
console.log('  aviso "Gerado automaticamente..." presente:', s.avisoCodigoAuto, '(esperado false)');
console.log('  botao "Definir manualmente" presente:', s.temDefinirManualmente, '(esperado true)');

console.log('=== 2. reter por ===');
console.log('  ' + JSON.stringify(s.checkboxes.filter(c=>/sempre|vence/i.test(c.label||''))));
console.log('  campo de anos:', JSON.stringify(s.numeros));

console.log('=== 1. grupo/setor com visibilidade PRIVADO (padrao) ===');
console.log('  campo de grupo visivel:', s.temCampoGrupo, '(esperado false)');

// mudar para Restrito
await page.evaluate(() => {
  [...document.querySelectorAll('button')].find(b=>/^restrito$/i.test((b.textContent||'').trim()))?.click();
});
await new Promise(r=>setTimeout(r,800));
s = await snap();
console.log('=== 1. apos marcar RESTRITO ===');
console.log('  campo de grupo visivel:', s.temCampoGrupo, '(esperado true)');
console.log('  obrigatorio (required):', s.grupoObrigatorio, '(esperado true)');
console.log('  opcoes:', JSON.stringify(s.opcoesGrupo));

// tentar submeter sem escolher setor
await page.evaluate(() => {
  const t = document.querySelector('input[type=text]');
  const inputs=[...document.querySelectorAll('input[type=text]')];
  const titulo = inputs[1] || inputs[0];
  if (titulo) { const set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    set.call(titulo,'Doc de teste'); titulo.dispatchEvent(new Event('input',{bubbles:true})); }
  [...document.querySelectorAll('button')].find(b=>/^criar|^salvar|^create/i.test((b.textContent||'').trim()))?.click();
});
await new Promise(r=>setTimeout(r,1200));
const erro = await page.evaluate(() => {
  const m = document.body.innerText.match(/Escolha o setor[^\n]*/);
  return m ? m[0] : null;
});
console.log('  submeter sem setor ->', erro ? `recusado: "${erro.slice(0,70)}..."` : 'PASSOU (ERRADO)');
console.log('ERROS', JSON.stringify(errors.slice(0,3)));
await browser.close();
