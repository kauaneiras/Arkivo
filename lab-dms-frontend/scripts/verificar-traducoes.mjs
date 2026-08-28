/**
 * Paridade dos dicionários de tradução.
 *
 * Uma chave em falta não se manifesta como erro em lado nenhum: manifesta-se
 * como o nome técnico da chave impresso no ecrã do utilizador — 'users.delete'
 * onde devia ler-se "Apagar conta". O compilador não tem como reparar, porque
 * um dicionário é um Record<string, string> e qualquer chave é válida.
 *
 * Este script compara cada idioma com o português, que é a língua de origem, e
 * falha se faltar ou sobrar alguma. Também apanha o caso silencioso de uma
 * tradução ter ficado igual ao original — o que às vezes é correto ("PDF") e às
 * vezes é uma linha por traduzir, por isso é aviso e não erro.
 *
 * Uso:  node scripts/verificar-traducoes.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'lib', 'locales');

/** Lê as chaves e valores de um ficheiro de dicionário sem o executar. */
function readDict(file) {
  const src = fs.readFileSync(path.join(dir, file), 'utf8');
  const out = new Map();
  // Cada entrada é   'chave': 'valor',   possivelmente com aspas escapadas.
  const re = /^\s{2}'([^']+)':\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,?\s*$/gm;
  let m;
  while ((m = re.exec(src)) !== null) out.set(m[1], m[2] ?? m[3] ?? '');
  return out;
}

const base = readDict('pt.ts');
const others = ['en', 'es', 'fr', 'de', 'zh'];

let failed = false;
console.log(`pt (origem): ${base.size} chaves\n`);

for (const lang of others) {
  const file = `${lang}.ts`;
  if (!fs.existsSync(path.join(dir, file))) {
    console.error(`FALHA  ${file} não existe`);
    failed = true;
    continue;
  }
  const dict = readDict(file);
  const missing = [...base.keys()].filter((k) => !dict.has(k));
  const extra = [...dict.keys()].filter((k) => !base.has(k));
  const identical = [...dict.entries()].filter(([k, v]) => base.get(k) === v && v.length > 3);

  if (missing.length === 0 && extra.length === 0) {
    const note = identical.length ? `  (${identical.length} iguais ao português)` : '';
    console.log(`OK     ${lang}: ${dict.size} chaves${note}`);
  } else {
    failed = true;
    console.error(`FALHA  ${lang}: ${dict.size} chaves`);
    if (missing.length) {
      console.error(`         em falta (${missing.length}): ${missing.slice(0, 12).join(', ')}` +
        (missing.length > 12 ? ` … e mais ${missing.length - 12}` : ''));
    }
    if (extra.length) {
      console.error(`         a mais (${extra.length}): ${extra.slice(0, 12).join(', ')}`);
    }
  }
}

console.log();
if (failed) {
  console.error('Há dicionários incompletos. Uma chave em falta aparece ao utilizador como o próprio nome dela.');
  process.exit(1);
}
console.log('Todos os idiomas cobrem as mesmas chaves.');
