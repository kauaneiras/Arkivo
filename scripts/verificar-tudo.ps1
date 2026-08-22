# ============================================================
# Lab DMS — Verificacao completa
#
#   powershell -ExecutionPolicy Bypass -File scripts\verificar-tudo.ps1
#
# Reconstroi, sobe, e testa TODOS os controles ISO ponta a ponta.
# Grava scripts\verificacao.txt — cole esse arquivo no chat.
# ============================================================

$ErrorActionPreference = "Continue"
$out = Join-Path $PSScriptRoot "verificacao.txt"
$API = "http://localhost:8080/api/v1"
$PASS = 0; $FAIL = 0
"Lab DMS - verificacao $(Get-Date -Format 'yyyy-MM-dd HH:mm')" | Set-Content $out

function Log($t) { Write-Host $t; Add-Content $out $t }
function Section($t) { Log "`n=== $t ===" }
function Step($name, [scriptblock]$block) {
    try {
        $r = & $block
        $script:PASS++
        $extra = if ($r) { "  ($r)" } else { "" }
        Log "  [OK]   $name$extra"
    } catch {
        $script:FAIL++
        Log "  [FALHA] $name"
        Log "          $($_.Exception.Message)"
        if ($_.ErrorDetails.Message) { Log "          $($_.ErrorDetails.Message)" }
    }
}
function Login($email, $pass) {
    $b = @{ username = $email; password = $pass } | ConvertTo-Json
    (Invoke-RestMethod -Method Post -Uri "$API/auth/login" -ContentType "application/json" -Body $b).accessToken
}
function H($t) { @{ Authorization = "Bearer $t" } }

# ------------------------------------------------------------
Section "0. Build e subida"
Log "Reconstruindo (os testes do backend rodam dentro do build)..."
$build = docker compose up -d --build 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Log "BUILD FALHOU:"
    Log $build
    Log "`n>>> Cole este arquivo no chat. O erro acima e o que precisa ser corrigido."
    exit 1
}
Log "Build OK."

Log "Aguardando o backend responder..."
$ok = $false
foreach ($i in 1..60) {
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 3
        if ($h.status -eq "UP") { $ok = $true; break }
    } catch { Start-Sleep -Seconds 3 }
}
if (-not $ok) {
    Log "BACKEND NAO SUBIU. Ultimas linhas do log:"
    docker compose logs backend --tail 80 2>&1 | Out-String | ForEach-Object { Log $_ }
    Log "`n>>> Cole este arquivo no chat."
    exit 1
}
Log "Backend UP."

Section "1. Migrations aplicadas (V1 a V8)"
$mig = docker compose exec -T postgres psql -U labdms -d labdms -t -c `
    "SELECT version || ' ' || CASE WHEN success THEN 'OK' ELSE 'FALHOU' END FROM flyway_schema_history ORDER BY installed_rank;" 2>&1 | Out-String
Log $mig
if ($mig -match "FALHOU") { Log ">>> ALGUMA MIGRATION FALHOU — este e o problema principal." }

Section "2. Autenticacao"
$admin = $null; $writer = $null; $reviewer = $null
Step "login admin"    { $script:admin    = Login "admin@email.com" "123456";    "" }
Step "login writer"   { $script:writer   = Login "writer@email.com" "123456";   "" }
Step "login reviewer" { $script:reviewer = Login "reviewer@email.com" "123456"; "" }
if (-not $admin) { Log "Sem admin, abortando."; exit 1 }
$A = H $admin; $W = H $writer; $R = H $reviewer

Section "3. Listagem de documentos (bug do parametro nulo)"
$docId = $null
Step "GET /documents retorna itens" {
    $d = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $A
    if ($d.content.Count -lt 1) { throw "lista vazia (total=$($d.totalElements))" }
    $script:docId = $d.content[0].id
    "$($d.content.Count) documentos"
}
Step "filtro por autor" {
    $u = Invoke-RestMethod -Uri "$API/users?status=ACTIVE" -Headers $A
    $w = ($u | Where-Object { $_.email -eq "writer@email.com" }).id
    $d = Invoke-RestMethod -Uri "$API/documents?createdBy=$w&size=50" -Headers $A
    "$($d.content.Count) do writer"
}
Step "listagem traz autor e revisor" {
    $d = Invoke-RestMethod -Uri "$API/documents?size=5" -Headers $A
    if (-not ($d.content[0].PSObject.Properties.Name -contains 'authorName')) { throw "campo authorName ausente" }
    ""
}

Section "4. Criacao com codigo automatico"
$novoId = $null
Step "GET /documents/next-code" {
    $n = Invoke-RestMethod -Uri "$API/documents/next-code?docType=IT" -Headers $W
    if ($n.code -notmatch '^IT-\d{3}$') { throw "codigo invalido: $($n.code)" }
    $n.code
}
Step "POST /documents (codigo nulo -> backend gera)" {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "verificacao" + "`n" + "\end{document}"
    $b = @{ code = $null; title = "Verificacao $(Get-Date -Format HHmmss)"; docType = "IT";
            documentGroupId = $null; initialLatexSource = $tex; tags = @();
            reviewIntervalMonths = 24; retentionYears = 5; requiresAcknowledgement = $true } | ConvertTo-Json
    $d = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $b
    $script:novoId = $d.id
    $d.code
}
Step "campos ISO vieram preenchidos" {
    $d = Invoke-RestMethod -Uri "$API/documents/$novoId" -Headers $W
    if (-not $d.nextReviewDate) { throw "nextReviewDate vazio" }
    if ($d.retentionYears -ne 5) { throw "retentionYears=$($d.retentionYears)" }
    "proxima revisao $($d.nextReviewDate), retencao $($d.retentionYears)a"
}

Section "5. LaTeX (compilacao e previa)"
Step "preview compila e devolve PDF de verdade" {
    $tex = "\documentclass{article}\usepackage[brazil]{babel}" + "`n" + "\begin{document}Teste\end{document}"
    $b = @{ latexSource = $tex } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP "v-preview.pdf"
    Invoke-WebRequest -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" `
        -Body $b -OutFile $tmp | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    $sig = [System.Text.Encoding]::ASCII.GetString($bytes[0..3])
    if ($sig -ne "%PDF") { throw "nao e PDF (assinatura '$sig') - possivel base64 do Jackson" }
    "$($bytes.Length) bytes"
}
Step "documento com \input inexistente NAO trava" {
    $tex = "\documentclass{article}\begin{document}\input{nao-existe}\end{document}"
    $b = @{ latexSource = $tex } | ConvertTo-Json
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try { Invoke-RestMethod -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" -Body $b -TimeoutSec 90 }
    catch { }
    $sw.Stop()
    if ($sw.Elapsed.TotalSeconds -gt 75) { throw "demorou $([int]$sw.Elapsed.TotalSeconds)s - travou" }
    "respondeu em $([int]$sw.Elapsed.TotalSeconds)s"
}
Step "PDF de versao nao compilada devolve 404 (nao 200 vazio)" {
    $c = Invoke-RestMethod -Uri "$API/documents/$novoId/versioning/commits" -Headers $W
    try {
        Invoke-WebRequest -Uri "$API/latex/pdf/$($c[0].id)" -Headers $W -OutFile (Join-Path $env:TEMP "v.pdf") | Out-Null
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $env:TEMP "v.pdf"))
        if ($bytes.Length -eq 0) { throw "200 com corpo vazio - bug do 'Failed to load PDF'" }
        "PDF armazenado: $($bytes.Length) bytes"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 404) { "404 correto (ainda nao compilado)" }
        else { throw }
    }
}

Section "6. ISO 8.3.2 c - motivo da alteracao"
Step "salvar versao com motivo" {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "v2" + "`n" + "\end{document}"
    $b = @{ latexSource = $tex; message = "Segunda versao"; changeReason = "Correcao apos ensaio EQ-2026-014" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/documents/$novoId/versioning/commits" -Headers $W -ContentType "application/json" -Body $b | Out-Null
    $c = Invoke-RestMethod -Uri "$API/documents/$novoId/versioning/commits" -Headers $W
    $comReason = $c | Where-Object { $_.changeReason -match "EQ-2026-014" }
    if (-not $comReason) { throw "changeReason nao persistiu" }
    "motivo gravado e devolvido no historico"
}

Section "7. ISO 8.3.2 b - revisao periodica"
Step "GET /documents/due-for-review" {
    $d = Invoke-RestMethod -Uri "$API/documents/due-for-review?withinDays=3650" -Headers $A
    "$($d.Count) documento(s) na janela"
}
Step "POST /documents/{id}/periodic-review" {
    $b = @{ notes = "Verificado, continua valido" } | ConvertTo-Json
    $d = Invoke-RestMethod -Method Post -Uri "$API/documents/$docId/periodic-review" -Headers $A -ContentType "application/json" -Body $b
    if (-not $d.nextReviewDate) { throw "nao reagendou" }
    "reagendado para $($d.nextReviewDate)"
}
Step "revisao periodica NAO cria versao nova" {
    $antes = (Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits" -Headers $A).Count
    $b = @{ notes = "segunda checagem" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/documents/$docId/periodic-review" -Headers $A -ContentType "application/json" -Body $b | Out-Null
    $depois = (Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits" -Headers $A).Count
    if ($antes -ne $depois) { throw "criou versao ($antes -> $depois)" }
    "$depois versoes antes e depois"
}
Step "job de lembrete roda sob demanda (@Scheduled)" {
    $r = Invoke-RestMethod -Method Post -Uri "$API/documents/run-review-reminders" -Headers $A
    "$($r.remindersSent) lembrete(s) enviado(s)"
}
Step "notificacao REVIEW_DUE chega ao sino" {
    $n = Invoke-RestMethod -Uri "$API/notifications" -Headers $A
    $due = $n | Where-Object { $_.type -eq "REVIEW_DUE" }
    if (-not $due) { "nenhuma pendente no momento (ok se nada esta vencido)" }
    else { "$($due.Count) notificacao(oes)" }
}

Section "8. ISO 8.3.2 d - ciencia de leitura"
Step "GET .../acknowledgements/me" {
    $s = Invoke-RestMethod -Uri "$API/documents/$novoId/acknowledgements/me" -Headers $W
    if (-not $s.required) { throw "deveria exigir ciencia (criado com requiresAcknowledgement=true)" }
    "exigida=$($s.required), ja deu ciencia=$($s.acknowledged)"
}
Step "POST ciencia + idempotencia" {
    Invoke-RestMethod -Method Post -Uri "$API/documents/$novoId/acknowledgements" -Headers $W | Out-Null
    Invoke-RestMethod -Method Post -Uri "$API/documents/$novoId/acknowledgements" -Headers $W | Out-Null
    $l = Invoke-RestMethod -Uri "$API/documents/$novoId/acknowledgements" -Headers $W
    $meus = $l | Where-Object { $_.userEmail -eq "writer@email.com" }
    if ($meus.Count -ne 1) { throw "duplicou: $($meus.Count) registros" }
    "1 registro apos 2 chamadas"
}
Step "GET .../pending lista quem falta" {
    $p = Invoke-RestMethod -Uri "$API/documents/$novoId/acknowledgements/pending" -Headers $A
    "$($p.Count) pessoa(s) pendente(s)"
}

Section "9. ISO 8.3.1 - documentos externos"
$extId = $null
Step "POST /external-documents" {
    $b = @{ code = "IPC-A-610-$(Get-Date -Format HHmmss)"; title = "Aceitabilidade de Montagens Eletronicas";
            issuer = "IPC"; edition = "IPC-A-610H"; docKind = "STANDARD";
            verificationIntervalMonths = 12; status = "ACTIVE" } | ConvertTo-Json
    $e = Invoke-RestMethod -Method Post -Uri "$API/external-documents" -Headers $A -ContentType "application/json" -Body $b
    $script:extId = $e.id
    "$($e.code) / $($e.edition), proxima verificacao $($e.nextVerificationDate)"
}
Step "POST .../verify registra quem confirmou" {
    $e = Invoke-RestMethod -Method Post -Uri "$API/external-documents/$extId/verify" -Headers $A
    if (-not $e.lastVerifiedByName) { throw "nao registrou quem verificou" }
    "verificado por $($e.lastVerifiedByName)"
}
Step "GET .../due-for-verification" {
    $d = Invoke-RestMethod -Uri "$API/external-documents/due-for-verification?withinDays=3650" -Headers $A
    "$($d.Count) na janela"
}

Section "10. ISO 8.3.2 f - marca d'agua e versao vigente"
Step "eleger versao antiga como vigente" {
    $c = Invoke-RestMethod -Uri "$API/documents/$novoId/versioning/commits" -Headers $A
    $antiga = $c | Sort-Object revision | Select-Object -First 1
    Invoke-RestMethod -Method Put -Uri "$API/documents/$novoId/official-version/$($antiga.id)" -Headers $A | Out-Null
    $d = Invoke-RestMethod -Uri "$API/documents/$novoId" -Headers $A
    if ($d.officialCommitId -ne $antiga.id) { throw "nao mudou a vigente" }
    "vigente agora e a v$($antiga.revision), historico preservado ($($c.Count) versoes)"
}
Step "obsoletar documento e baixar PDF marcado" {
    $b = @{ status = "OBSOLETE" } | ConvertTo-Json
    Invoke-RestMethod -Method Patch -Uri "$API/documents/$novoId/status" -Headers $A -ContentType "application/json" -Body $b | Out-Null
    $c = Invoke-RestMethod -Uri "$API/documents/$novoId/versioning/commits" -Headers $A
    $tmp = Join-Path $env:TEMP "v-obsoleto.pdf"
    try {
        Invoke-WebRequest -Uri "$API/latex/pdf/$($c[0].id)" -Headers $A -OutFile $tmp | Out-Null
        $bytes = [System.IO.File]::ReadAllBytes($tmp)
        if ([System.Text.Encoding]::ASCII.GetString($bytes[0..3]) -ne "%PDF") { throw "nao e PDF" }
        "PDF com marca gerado ($($bytes.Length) bytes) - abra e confira a tarja vermelha"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 404) { "sem PDF armazenado (compile no editor para testar a marca)" }
        else { throw }
    }
}

Section "11. Papeis multiplos e bloqueio de auto-revisao"
Step "atribuir EDITOR + REVIEWER ao mesmo usuario" {
    $u = Invoke-RestMethod -Uri "$API/users?status=ACTIVE" -Headers $A
    $w = ($u | Where-Object { $_.email -eq "writer@email.com" }).id
    $b = @{ roleNames = @("ROLE_EDITOR", "ROLE_REVIEWER") } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$API/users/$w/roles" -Headers $A -ContentType "application/json" -Body $b | Out-Null
    $u2 = Invoke-RestMethod -Uri "$API/users?status=ACTIVE" -Headers $A
    $r = ($u2 | Where-Object { $_.email -eq "writer@email.com" }).role
    if ($r -notmatch "EDITOR" -or $r -notmatch "REVIEWER") { throw "papeis: $r" }
    "writer agora e: $r"
}
Step "usuario NAO aprova o proprio documento" {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "auto" + "`n" + "\end{document}"
    $b = @{ code = $null; title = "Auto-revisao $(Get-Date -Format HHmmss)"; docType = "PQ";
            documentGroupId = $null; initialLatexSource = $tex; tags = @() } | ConvertTo-Json
    $d = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $b
    $b2 = @{ title = "Auto"; description = "x" } | ConvertTo-Json
    $rev = Invoke-RestMethod -Method Post -Uri "$API/documents/$($d.id)/reviews" -Headers $W -ContentType "application/json" -Body $b2
    $b3 = @{ decision = "APPROVED"; comments = "aprovando o meu proprio" } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/reviews/$($rev.id)/decision" -Headers $W -ContentType "application/json" -Body $b3
        throw "APROVOU O PROPRIO DOCUMENTO - falha de conformidade!"
    } catch {
        if ($_.Exception.Message -match "PROPRIO DOCUMENTO") { throw }
        "bloqueado corretamente (HTTP $($_.Exception.Response.StatusCode.value__))"
    }
}

Section "12. Lista mestra (registro obrigatorio)"
Step "GET /documents/master-list" {
    $tmp = Join-Path $env:TEMP "lista-mestra.csv"
    Invoke-WebRequest -Uri "$API/documents/master-list" -Headers $A -OutFile $tmp | Out-Null
    $linhas = (Get-Content $tmp).Count
    if ($linhas -lt 2) { throw "CSV vazio" }
    "$($linhas - 1) documento(s) no CSV -> $tmp"
}

# ------------------------------------------------------------
Section "Erros no log durante a verificacao"
$log = docker compose logs backend --tail 200 2>&1 | Out-String
$erros = $log -split "`n" | Where-Object {
    $_ -match "could not determine data type|No enum constant|PSQLException|NullPointer|APPLICATION FAILED|Error creating bean"
} | Select-Object -Last 15
if ($erros) { $erros | ForEach-Object { Log "  $_" } } else { Log "  Nenhum." }

Log "`n============================================"
Log " RESULTADO: $PASS OK, $FAIL FALHAS"
Log "============================================"
Write-Host "`nRelatorio salvo em: $out" -ForegroundColor Cyan
Write-Host "Cole o conteudo desse arquivo no chat." -ForegroundColor Cyan
exit $(if ($FAIL -eq 0) { 0 } else { 1 })
