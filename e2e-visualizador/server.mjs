import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const DIST = process.env.DIST;
const PDF  = fs.readFileSync(process.env.PDF);
const TEX  = fs.readFileSync(process.env.TEX, 'utf8');

const DOC = 'doc-1', COMMIT = 'commit-1';
const MIME = { '.html':'text/html', '.js':'application/javascript', '.mjs': (process.env.BREAK_MJS ? 'application/octet-stream' : 'text/javascript'),
               '.css':'text/css', '.svg':'image/svg+xml', '.json':'application/json',
               '.woff2':'font/woff2', '.png':'image/png', '.ico':'image/x-icon' };

const json = (res, body, code=200) => {
  res.writeHead(code, {'Content-Type':'application/json'});
  res.end(JSON.stringify(body));
};
const pdf = (res) => {
  res.writeHead(200, {'Content-Type':'application/pdf','Content-Length':PDF.length,
                      'X-Pdf-Pages':'2','X-Latex-Problems':'0',
                      'Access-Control-Expose-Headers':'X-Pdf-Pages, X-Latex-Problems, X-Latex-Error-List'});
  res.end(PDF);
};

const hits = [];
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  if (p.startsWith('/api/')) hits.push(`${req.method} ${p}`);

  // ---- API simulada ----
  if (p === `/api/v1/documents/${DOC}`)
    return json(res, { id: DOC, code:'PO-001', title:'Teste do visualizador', docType:'PO',
      status:'DRAFT', currentCommitId:COMMIT, officialCommitId:COMMIT, createdBy:'u1',
      createdAt:new Date().toISOString(), updatedAt:new Date().toISOString(), tags:[],
      reviewIntervalMonths:24, retentionYears:5, visibility:'PRIVATE' });

  if (p === `/api/v1/documents/${DOC}/versioning/commits`)
    return json(res, [{ id:COMMIT, message:'versão inicial', authorName:'Administrador',
      createdAt:new Date().toISOString(), revision:1, isCurrent:true, isOfficial:true,
      branchName:'main', parentCommitId:null }]);

  if (p === `/api/v1/documents/${DOC}/versioning/commits/${COMMIT}/content`)
    return json(res, { content: TEX });

  if (p === `/api/v1/documents/${DOC}/files`)
    return json(res, []);                       // documento de ficheiro único

  if (p === `/api/v1/latex/pdf/${COMMIT}`) return pdf(res);
  if (p === '/api/v1/latex/preview' || p.startsWith('/api/v1/latex/preview/project/')) return pdf(res);

  if (p.startsWith('/api/')) return json(res, []);   // tudo o resto: vazio, não 404

  // ---- estáticos ----
  let f = path.join(DIST, p === '/' ? 'index.html' : p);
  if (!fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html');
  const ext = path.extname(f);
  res.writeHead(200, {'Content-Type': MIME[ext] || 'application/octet-stream'});
  res.end(fs.readFileSync(f));
}).listen(8100, () => console.log('mock em :8100'));

process.on('SIGTERM', () => { console.log('HITS:'+JSON.stringify(hits)); process.exit(0); });
