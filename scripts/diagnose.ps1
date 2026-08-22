# ============================================================
# Lab DMS — Diagnostico
# Roda tudo e grava um relatorio unico para colar no chat.
#
#   powershell -ExecutionPolicy Bypass -File scripts\diagnose.ps1
#
# Saida: scripts\diagnostico.txt
# ============================================================

$ErrorActionPreference = "Continue"
$out = Join-Path $PSScriptRoot "diagnostico.txt"
$API = "http://localhost:8080/api/v1"
"" | Set-Content $out

function Section($title) {
    $line = "`n=== $title ==="
    Write-Host $line -ForegroundColor Cyan
    Add-Content $out $line
}
function Log($text) {
    Write-Host $text
    Add-Content $out $text
}

Section "1. Containers"
$ps = docker compose ps 2>&1 | Out-String
Log $ps

Section "2. Backend subiu? (ultimas linhas do log)"
$log = docker compose logs backend --tail 400 2>&1 | Out-String

# Erros de startup sao a causa mais comum de "tudo quebrado"
$startupFail = $log -match "APPLICATION FAILED TO START|Error creating bean|FlywayException|Migration .* failed|Caused by"
if ($startupFail) {
    Log "!! O BACKEND TEVE ERRO DE INICIALIZACAO — este e o problema principal:"
    $log -split "`n" | Where-Object {
        $_ -match "APPLICATION FAILED TO START|Error creating bean|FlywayException|Caused by|Migration|SQLState|ERROR"
    } | Select-Object -Last 40 | ForEach-Object { Log "   $_" }
} else {
    Log "Nenhum erro de startup detectado."
}

Section "3. Erros recentes no log (SQL / excecoes)"
$erros = $log -split "`n" | Where-Object {
    $_ -match "could not determine data type|IllegalArgumentException|PSQLException|DataIntegrityViolation|No enum constant|500|ERROR"
} | Select-Object -Last 30
if ($erros) { $erros | ForEach-Object { Log "   $_" } } else { Log "Nenhum." }

Section "4. Health check"
try {
    $h = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 10
    Log "status: $($h.status)"
} catch {
    Log "FALHOU: $($_.Exception.Message)"
    Log ">> O backend nao esta respondendo. Veja a secao 2."
}

Section "5. Migrations aplicadas"
$mig = docker compose exec -T postgres psql -U labdms -d labdms -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;" 2>&1 | Out-String
Log $mig

Section "6. O que existe no banco"
$counts = docker compose exec -T postgres psql -U labdms -d labdms -c "SELECT 'documents' t, COUNT(*) FROM documents UNION ALL SELECT 'users', COUNT(*) FROM users UNION ALL SELECT 'branches', COUNT(*) FROM branches UNION ALL SELECT 'commits', COUNT(*) FROM commits UNION ALL SELECT 'document_groups', COUNT(*) FROM document_groups;" 2>&1 | Out-String
Log $counts

Log "Codigos dos documentos:"
$codes = docker compose exec -T postgres psql -U labdms -d labdms -c "SELECT code, status, doc_type FROM documents ORDER BY code;" 2>&1 | Out-String
Log $codes

Log "Status das branches (tem que ser OPEN/MERGED/CLOSED):"
$bs = docker compose exec -T postgres psql -U labdms -d labdms -c "SELECT status, COUNT(*) FROM branches GROUP BY status;" 2>&1 | Out-String
Log $bs

Section "7. Login"
$token = $null
try {
    $body = @{ username = "writer@email.com"; password = "123456" } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$API/auth/login" -ContentType "application/json" -Body $body -TimeoutSec 15
    $token = $r.accessToken
    Log "OK — login writer@email.com funcionou"
} catch {
    Log "FALHOU: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Log "   corpo: $($_.ErrorDetails.Message)" }
}

if (-not $token) {
    Log "`nSem token, nao da para testar o resto. Relatorio salvo em: $out"
    exit 1
}
$H = @{ Authorization = "Bearer $token" }

Section "8. GET /documents  (o sintoma: lista vazia)"
try {
    $docs = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $H -TimeoutSec 20
    Log "OK — HTTP 200"
    Log "totalElements: $($docs.totalElements)"
    Log "itens retornados: $($docs.content.Count)"
    if ($docs.content.Count -gt 0) {
        Log "codigos: $(($docs.content | ForEach-Object { $_.code }) -join ', ')"
    } else {
        Log ">> Respondeu 200 mas veio VAZIO — ver secao 6 (o banco tem documentos?)"
    }
} catch {
    Log "FALHOU: HTTP $($_.Exception.Response.StatusCode.value__) — $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Log "   corpo: $($_.ErrorDetails.Message)" }
    Log ">> Este e o erro que faz a lista aparecer vazia."
}

Section "9. GET /documents/next-code (codigo automatico)"
foreach ($tipo in @("IT", "PQ", "PO", "MQ")) {
    try {
        $nc = Invoke-RestMethod -Uri "$API/documents/next-code?docType=$tipo" -Headers $H -TimeoutSec 15
        Log "$tipo -> $($nc.code)"
    } catch {
        Log "$tipo -> FALHOU: HTTP $($_.Exception.Response.StatusCode.value__) $($_.ErrorDetails.Message)"
    }
}

Section "10. POST /documents (criar documento com codigo automatico)"
try {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "diagnostico" + "`n" + "\end{document}"
    $body = @{ code = $null; title = "Diagnostico $(Get-Date -Format HHmmss)"; docType = "IT";
               documentGroupId = $null; initialLatexSource = $tex; tags = @() } | ConvertTo-Json
    $novo = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $H -ContentType "application/json" -Body $body -TimeoutSec 30
    Log "OK — documento criado: $($novo.code) (id $($novo.id))"

    $depois = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $H -TimeoutSec 20
    if (($depois.content | Where-Object { $_.id -eq $novo.id })) {
        Log "OK — o documento novo APARECE na listagem"
    } else {
        Log ">> Criou mas NAO aparece na listagem."
    }
} catch {
    Log "FALHOU: HTTP $($_.Exception.Response.StatusCode.value__) — $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Log "   corpo: $($_.ErrorDetails.Message)" }
}

Section "11. Erros gerados durante este diagnostico"
$logDepois = docker compose logs backend --tail 120 2>&1 | Out-String
$novosErros = $logDepois -split "`n" | Where-Object {
    $_ -match "could not determine data type|No enum constant|IllegalArgumentException|PSQLException|Exception"
} | Select-Object -Last 25
if ($novosErros) { $novosErros | ForEach-Object { Log "   $_" } } else { Log "Nenhum." }

Write-Host "`n=========================================" -ForegroundColor Green
Write-Host " Relatorio salvo em: $out" -ForegroundColor Green
Write-Host " Cole o conteudo desse arquivo no chat." -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
