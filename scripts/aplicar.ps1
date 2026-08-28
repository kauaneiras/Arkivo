# Aplica as alterações e mostra, passo a passo, o que funcionou.
#
# Existe porque o `docker compose up --build` esconde a informação que importa:
# se a construção do backend falha, o compose aborta ANTES de recriar qualquer
# contentor — o frontend continua a servir o pacote antigo, e tudo parece por
# fazer. Este script constrói cada parte separadamente e diz qual falhou.
#
# Uso:  powershell -ExecutionPolicy Bypass -File scripts\aplicar.ps1

$ErrorActionPreference = 'Continue'
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

function Passo($n, $texto) { Write-Host ""; Write-Host "[$n] $texto" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "    OK    $t" -ForegroundColor Green }
function Falha($t){ Write-Host "    FALHA $t" -ForegroundColor Red }

Passo 1 "Construindo o FRONTEND (itens 2 e 3 são só de interface)"
docker compose build frontend 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -eq 0) { Ok "frontend construído" } else { Falha "frontend não construiu"; exit 1 }

Passo 2 "Construindo o BACKEND (aqui é onde a construção costuma abortar)"
$saidaBackend = docker compose build backend 2>&1
if ($LASTEXITCODE -eq 0) {
    Ok "backend construído — os testes passaram"
} else {
    Falha "backend NÃO construiu. A causa está abaixo:"
    $saidaBackend | Select-String -Pattern "ERROR|error:|Caused by|Tests run|BUILD FAILURE" |
        Select-Object -First 20 | ForEach-Object { Write-Host "      $_" -ForegroundColor Yellow }
    Write-Host ""
    Write-Host "    O frontend JÁ foi construído, então vou subir só ele para você" -ForegroundColor Yellow
    Write-Host "    conseguir ver os itens 2 e 3 enquanto o backend é corrigido." -ForegroundColor Yellow
    docker compose up -d --force-recreate --no-deps frontend 2>&1 | Select-Object -Last 2
    exit 1
}

Passo 3 "Subindo tudo, recriando os contentores"
docker compose up -d --force-recreate 2>&1 | Select-Object -Last 5

Passo 4 "Esperando o backend responder"
$pronto = $false
foreach ($i in 1..30) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $pronto = $true; break }
    } catch { }
}
if ($pronto) { Ok "backend no ar" } else { Falha "backend não respondeu em 60s"; docker compose logs backend --tail 30 }

Passo 5 "Confirmando que as migrações aplicaram (V13 = base limpa)"
docker exec lab-dms-postgres psql -U labdms -d labdms -t -c `
  "SELECT MAX(version) FROM flyway_schema_history WHERE success;" 2>&1 | ForEach-Object {
    if ($_ -match '\S') { Ok "última migração aplicada: v$($_.Trim())" }
}

Passo 6 "Confirmando os usuários (senha de todos: 123456)"
docker exec lab-dms-postgres psql -U labdms -d labdms -c `
  "SELECT email, status, length(password_hash) AS hash FROM users ORDER BY email;" 2>&1 |
  ForEach-Object { Write-Host "      $_" }

Passo 7 "Confirmando que documentos e logs foram limpos"
docker exec lab-dms-postgres psql -U labdms -d labdms -t -c `
  "SELECT 'documentos=' || (SELECT COUNT(*) FROM documents) || '  versoes=' || (SELECT COUNT(*) FROM commits) || '  auditoria=' || (SELECT COUNT(*) FROM audit_log);" 2>&1 |
  ForEach-Object { if ($_ -match '\S') { Ok $_.Trim() } }

Write-Host ""
Write-Host "Pronto. Abra http://localhost:3000 e faça CTRL+SHIFT+R" -ForegroundColor Green
Write-Host "(o nginx guarda o index.html em cache; um refresh normal serve o antigo)" -ForegroundColor DarkGray
