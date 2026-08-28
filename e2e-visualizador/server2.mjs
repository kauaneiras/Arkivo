import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const DIST = process.env.DIST;
const PDF  = fs.readFileSync(process.env.PDF);
// Quem está autenticado neste teste: 'admin' (pode criar) ou 'reader' (não pode).
const WHO  = process.env.WHO || 'admin';
const LANG = process.env.LANG_PREF || null;

const AUTHORITIES = WHO === 'admin'
  ? ['ROLE_ADMIN', 'DOCUMENT_CREATE', 'DOCUMENT_VIEW_ALL', 'DOCUMENT_EDIT']
  : ['ROLE_VIEWER', 'DOCUMENT_VIEW_ALL'];

const MIME = { '.html':'text/html', '.js':'application/javascript', '.mjs':'text/javascript',
               '.css':'text/css', '.svg':'image/svg+xml', '.woff2':'font/woff2', '.png':'image/png' };
const json = (res, b, c=200) => { res.writeHead(c, {'Content-Type':'application/json'}); res.end(JSON.stringify(b)); };

let savedLang = LANG;
const hits = [];

http.createServer((req, res) => {
  const p = new URL(req.url, 'http://x').pathname;
  if (p.startsWith('/api/')) hits.push(`${req.method} ${p}`);

  if (p === '/api/v1/auth/me')
    return json(res, { id:'u1', username:'t@t', email:'t@t', fullName:'Teste', roles: AUTHORITIES });

  if (p === '/api/v1/users/me/language') {
    if (req.method === 'PUT') {
      let body=''; req.on('data',d=>body+=d);
      return req.on('end', () => { savedLang = JSON.parse(body||'{}').language; json(res,{message:savedLang}); });
    }
    return json(res, { message: savedLang });
  }


  // --- utilizadores, para o item 4 ---
  if (p === '/api/v1/users' && req.method === 'GET') {
    const st = new URL(req.url,'http://x').searchParams.get('status');
    if (st === 'ACTIVE') return json(res, [
      { id:'u1', email:'admin@email.com', fullName:'Administrador', status:'ACTIVE', role:'ROLE_ADMIN' },
      { id:'u2', email:'writer@email.com', fullName:'Escritor', status:'ACTIVE', role:'ROLE_EDITOR' }]);
    if (st === 'INACTIVE') return json(res, [
      { id:'u3', email:'antigo@email.com', fullName:'Ex-Colaborador', status:'INACTIVE', role:'ROLE_VIEWER' }]);
    return json(res, []);
  }
  if (p === '/api/v1/users/roles')
    return json(res, [{id:'r1',name:'ROLE_ADMIN'},{id:'r2',name:'ROLE_EDITOR'},{id:'r3',name:'ROLE_VIEWER'}]);
  if (/^\/api\/v1\/users\/[^/]+\/status$/.test(p) && req.method === 'PUT') {
    let b=''; req.on('data',d=>b+=d);
    return req.on('end',()=>json(res,{message:'Conta '+(JSON.parse(b||'{}').status==='ACTIVE'?'reativada.':'desativada.')}));
  }
  if (/^\/api\/v1\/users\/[^/]+$/.test(p) && req.method === 'DELETE') {
    // u2 tem historico -> recusa explicativa; u3 nao tem -> apaga
    if (p.endsWith('/u2')) return json(res, { message:'Esta conta tem 12 registo(s) no sistema (documentos, versoes, revisoes ou auditoria) e apaga-la deixaria esse historico sem responsavel. Desative-a: o acesso e retirado e o historico fica intacto.' }, 400);
    return json(res, { message:'Conta apagada.' });
  }
  if (p === '/api/v1/documents/groups')
    return json(res, [{ id:'g1', name:'Soldagem' }, { id:'g2', name:'Qualidade' }]);

  if (p === '/api/v1/documents' && req.method === 'POST') {
    // O servidor recusa quem não tem a permissão — a tela é só o reflexo disto.
    if (!AUTHORITIES.includes('DOCUMENT_CREATE'))
      return json(res, { code:'FORBIDDEN', message:'Acesso negado.' }, 403);
    return json(res, { id:'doc-1', code:'PO-001' }, 201);
  }

  if (p.startsWith('/api/v1/latex/')) {
    res.writeHead(200, {'Content-Type':'application/pdf','Content-Length':PDF.length});
    return res.end(PDF);
  }
  if (p.startsWith('/api/v1/documents') && p.endsWith('/documents'))
    return json(res, { content: [], totalElements: 0, totalPages: 0, number: 0 });
  if (p.startsWith('/api/')) return json(res, []);

  let f = path.join(DIST, p === '/' ? 'index.html' : p);
  if (!fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html');
  res.writeHead(200, {'Content-Type': MIME[path.extname(f)] || 'application/octet-stream'});
  res.end(fs.readFileSync(f));
}).listen(8101, () => console.log(`mock :8101 como ${WHO}`));
