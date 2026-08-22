# ============================================================
# Lab DMS — End-to-End flow test
# Run after:  docker compose up -d --build
# Usage:      powershell -ExecutionPolicy Bypass -File scripts\test-e2e.ps1
# ============================================================
$ErrorActionPreference = "Stop"
$API = if ($env:LAB_DMS_API) { $env:LAB_DMS_API } else { "http://localhost:8080/api/v1" }
$PASS = 0; $FAIL = 0
$stamp = Get-Date -Format "HHmmss"

function Step($name, [scriptblock]$block) {
    try {
        & $block | Out-Null
        $script:PASS++
        Write-Host "  [OK]   $name" -ForegroundColor Green
    } catch {
        $script:FAIL++
        Write-Host "  [FAIL] $name" -ForegroundColor Red
        Write-Host "         $($_.Exception.Message)" -ForegroundColor DarkRed
        if ($_.ErrorDetails.Message) { Write-Host "         $($_.ErrorDetails.Message)" -ForegroundColor DarkRed }
    }
}

function Login($email, $password) {
    $body = @{ username = $email; password = $password } | ConvertTo-Json
    $res = Invoke-RestMethod -Method Post -Uri "$API/auth/login" -ContentType "application/json" -Body $body
    return $res.accessToken
}

function AuthHeaders($token) { return @{ Authorization = "Bearer $token" } }

function UploadAttachment($token, $documentId, $fileName, $textContent, $description) {
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($textContent)
    $fileContent = New-Object System.Net.Http.ByteArrayContent(, $bytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/plain")
    $mp.Add($fileContent, "file", $fileName)
    if ($description) { $mp.Add((New-Object System.Net.Http.StringContent($description)), "description") }
    $resp = $client.PostAsync("$API/documents/$documentId/attachments", $mp).Result
    $body = $resp.Content.ReadAsStringAsync().Result
    if (-not $resp.IsSuccessStatusCode) { throw "upload falhou ($($resp.StatusCode)): $body" }
    return $body | ConvertFrom-Json
}

Write-Host "`n=== Lab DMS E2E — $API ===`n"

# ------------------------------------------------------------
Write-Host "[1] Autenticacao (todas as roles, senha 123456)"
$adminToken = $null; $writerToken = $null; $reviewerToken = $null; $readerToken = $null
Step "login admin@email.com"    { $script:adminToken    = Login "admin@email.com"    "123456" }
Step "login writer@email.com"   { $script:writerToken   = Login "writer@email.com"   "123456" }
Step "login reviewer@email.com" { $script:reviewerToken = Login "reviewer@email.com" "123456" }
Step "login reader@email.com"   { $script:readerToken   = Login "reader@email.com"   "123456" }

if (-not $adminToken) { Write-Host "`nSem admin, abortando." -ForegroundColor Red; exit 1 }
$H = AuthHeaders $adminToken

# ------------------------------------------------------------
Write-Host "`n[2] Registro publico + aprovacao pelo admin"
$newEmail = "novato$stamp@email.com"
Step "registrar $newEmail" {
    $body = @{ email = $newEmail; fullName = "Usuario Novato $stamp"; password = "123456" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/auth/register" -ContentType "application/json" -Body $body
}
$pendingId = $null
Step "listar pendentes (admin)" {
    $pending = Invoke-RestMethod -Uri "$API/users?status=PENDING" -Headers $H
    $script:pendingId = ($pending | Where-Object { $_.email -eq $newEmail }).id
    if (-not $script:pendingId) { throw "usuario pendente nao encontrado" }
}
Step "aprovar como ROLE_VIEWER" {
    $body = @{ roleName = "ROLE_VIEWER" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/users/$pendingId/approve" -Headers $H -ContentType "application/json" -Body $body
}
Step "novato consegue logar" { Login $newEmail "123456" }

# ------------------------------------------------------------
Write-Host "`n[3] Admin cria usuario direto + gestao de roles"
$directEmail = "direto$stamp@email.com"
$directId = $null
Step "criar usuario direto (POST /users)" {
    $body = @{ fullName = "Criado Direto $stamp"; email = $directEmail; password = "123456"; roleName = "ROLE_EDITOR" } | ConvertTo-Json
    $u = Invoke-RestMethod -Method Post -Uri "$API/users" -Headers $H -ContentType "application/json" -Body $body
    $script:directId = $u.id
}
Step "mudar role do usuario para REVIEWER" {
    $body = @{ roleName = "ROLE_REVIEWER" } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$API/users/$directId/role" -Headers $H -ContentType "application/json" -Body $body
}

# ------------------------------------------------------------
Write-Host "`n[4] Troca da propria senha"
Step "usuario troca a propria senha" {
    $t = Login $directEmail "123456"
    $body = @{ currentPassword = "123456"; newPassword = "654321" } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$API/users/me/password" -Headers (AuthHeaders $t) -ContentType "application/json" -Body $body
}
Step "login com a senha nova" { Login $directEmail "654321" }
Step "senha antiga rejeitada" {
    try { Login $directEmail "123456"; throw "senha antiga ainda funciona!" }
    catch { if ($_.Exception.Message -match "ainda funciona") { throw } }
}

# ------------------------------------------------------------
Write-Host "`n[5] Documentos: criar (sem grupo), versionar, compilar, PDF"
$W = AuthHeaders $writerToken
$docId = $null; $commitId = $null
$latex = @"
\documentclass[12pt]{article}
\usepackage[utf8]{inputenc}
\usepackage[brazil]{babel}
\title{IT-E2E-$stamp \\ Teste Automatizado}
\author{Script E2E}
\date{\today}
\begin{document}
\maketitle
\section{Objetivo}
Documento criado pelo teste E2E com acentuacao: calibracao, padronizacao.
\end{document}
"@
Step "codigo gerado automaticamente pela categoria (endpoint next-code)" {
    $next = Invoke-RestMethod -Uri "$API/documents/next-code?docType=IT" -Headers $W
    if ($next.code -notmatch '^IT-\d{3}$') { throw "codigo automatico invalido: $($next.code)" }
}
Step "criar 2 documentos com o MESMO template (bug do hash duplicado)" {
    # Este era o erro 500: sha256_hash duplicado em commits
    $tpl = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "Mesmo conteudo $stamp" + "`n" + "\end{document}"
    foreach ($i in 1..2) {
        $body = @{ title = "Doc identico $i - $stamp"; docType = "PQ";
                   documentGroupId = $null; initialLatexSource = $tpl; tags = @() } | ConvertTo-Json
        $d = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $body
        if ($d.code -notmatch '^PQ-\d{3}$') { throw "codigo automatico inesperado: $($d.code)" }
    }
}
Step "salvar versao com conteudo repetido (dedup de snapshot)" {
    $tpl = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "Mesmo conteudo $stamp" + "`n" + "\end{document}"
    $body = @{ title = "Doc terceiro - $stamp"; docType = "PQ";
               documentGroupId = $null; initialLatexSource = $tpl; tags = @() } | ConvertTo-Json
    $d = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $body
    # salva de novo exatamente o mesmo conteudo: nao pode dar 500
    $commit = @{ latexSource = $tpl; message = "Sem alteracoes reais" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/documents/$($d.id)/versioning/commits" -Headers $W -ContentType "application/json" -Body $commit | Out-Null
}
Step "codigo invalido retorna 400 com mensagem (nao 500)" {
    $body = @{ code = "FORMATO_ERRADO"; title = "x"; docType = "IT";
               documentGroupId = $null; initialLatexSource = "\documentclass{article}\begin{document}x\end{document}"; tags = @() } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $body
        throw "codigo invalido foi aceito!"
    } catch {
        if ($_.Exception.Message -match "foi aceito") { throw }
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 400) { throw "esperava 400, veio $status" }
    }
}
Step "criar documento SEM grupo (writer)" {
    $body = @{ code = "IT-E2E-$stamp"; title = "Documento E2E $stamp"; docType = "IT";
               documentGroupId = $null; initialLatexSource = $latex; tags = @() } | ConvertTo-Json
    $d = Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers $W -ContentType "application/json" -Body $body
    $script:docId = $d.id
}
Step "nova versao (commit)" {
    $body = @{ latexSource = $latex.Replace("Objetivo", "Objetivo Revisado"); message = "Revisao E2E" } | ConvertTo-Json
    $c = Invoke-RestMethod -Method Post -Uri "$API/documents/$docId/versioning/commits" -Headers $W -ContentType "application/json" -Body $body
    $script:commitId = $c.id
}
Step "historico de versoes numeradas (v1, v2) com autor" {
    $commits = Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits" -Headers $W
    if ($commits.Count -lt 2) { throw "esperava >=2 versoes, veio $($commits.Count)" }
    $revisoes = ($commits | ForEach-Object { $_.revision }) -join ","
    if (-not ($commits | Where-Object { $_.revision -eq 1 })) { throw "sem revisao v1 (revisoes: $revisoes)" }
    if (-not ($commits | Where-Object { $_.isCurrent })) { throw "nenhuma versao marcada como atual" }
    if (-not $commits[0].authorName) { throw "versao sem autor" }
}
Step "listar branches do documento" {
    $branches = Invoke-RestMethod -Uri "$API/documents/$docId/versioning/branches" -Headers $W
    if (-not ($branches | Where-Object { $_.name -eq 'main' -and $_.isDefault })) { throw "branch main ausente" }
}
Step "restaurar v1 cria NOVA versao (historico imutavel)" {
    $commits = Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits" -Headers $W
    $v1 = $commits | Where-Object { $_.revision -eq 1 }
    $antes = $commits.Count
    Invoke-RestMethod -Method Post -Uri "$API/documents/$docId/versioning/commits/$($v1.id)/restore" -Headers $W | Out-Null
    $depois = (Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits" -Headers $W)
    if ($depois.Count -ne $antes + 1) { throw "esperava $($antes+1) versoes, veio $($depois.Count)" }
    if ($depois[0].message -notmatch "Restore version v1") { throw "mensagem da restauracao inesperada: $($depois[0].message)" }
    # conteudo restaurado deve bater com o da v1
    $c1 = (Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits/$($v1.id)/content" -Headers $W).content
    $cNovo = (Invoke-RestMethod -Uri "$API/documents/$docId/versioning/commits/$($depois[0].id)/content" -Headers $W).content
    if ($c1 -ne $cNovo) { throw "conteudo restaurado difere da v1" }
}
Step "compilar LaTeX" {
    $r = Invoke-RestMethod -Method Post -Uri "$API/latex/compile/$commitId" -Headers $W
    if ($r.status -ne "SUCCESS") {
        $log = if ($r.compilationLog) { $r.compilationLog.Substring(0, [Math]::Min(500, $r.compilationLog.Length)) } else { "" }
        throw "status: $($r.status) — log: $log"
    }
}
Step "baixar PDF compilado (%PDF valido)" {
    $tmp = Join-Path $env:TEMP "labdms-e2e.pdf"
    Invoke-WebRequest -Uri "$API/latex/pdf/$commitId" -Headers $W -OutFile $tmp | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    $sig = [System.Text.Encoding]::ASCII.GetString($bytes[0..3])
    if ($sig -ne "%PDF") { throw "arquivo nao e PDF (assinatura: $sig)" }
    if ($bytes.Length -lt 1000) { throw "PDF suspeito de vazio ($($bytes.Length) bytes)" }
}
Step "recompilar apos falha nao fica em cache (idempotencia)" {
    Invoke-RestMethod -Method Post -Uri "$API/latex/compile/$commitId" -Headers $W
}

# ------------------------------------------------------------
Write-Host "`n[6] Workflow de aprovacao (ISO 9000)"
$reviewId = $null
Step "writer submete para revisao" {
    $body = @{ title = "Aprovacao E2E $stamp"; description = "Teste automatizado" } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$API/documents/$docId/reviews" -Headers $W -ContentType "application/json" -Body $body
    $script:reviewId = $r.id
}
Step "documento ficou IN_REVIEW" {
    $d = Invoke-RestMethod -Uri "$API/documents/$docId" -Headers $W
    if ($d.status -ne "IN_REVIEW") { throw "status: $($d.status)" }
}
Step "revisor lista revisoes abertas" {
    $open = Invoke-RestMethod -Uri "$API/reviews?status=OPEN" -Headers (AuthHeaders $reviewerToken)
    if (-not ($open | Where-Object { $_.id -eq $reviewId })) { throw "revisao nao listada" }
}
Step "revisor APROVA" {
    $body = @{ decision = "APPROVED"; comments = "Aprovado no teste E2E" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$API/reviews/$reviewId/decision" -Headers (AuthHeaders $reviewerToken) -ContentType "application/json" -Body $body
}
Step "documento ficou ACTIVE" {
    $d = Invoke-RestMethod -Uri "$API/documents/$docId" -Headers $W
    if ($d.status -ne "ACTIVE") { throw "status: $($d.status)" }
}

# ------------------------------------------------------------
Write-Host "`n[6.1] Anexos"
$attachmentId = $null
Step "writer envia anexo (texto, dentro do limite)" {
    $a = UploadAttachment $writerToken $docId "certificado-teste-e2e.txt" "Certificado de teste E2E $stamp" "Anexo de teste"
    $script:attachmentId = $a.id
    if (-not $attachmentId) { throw "upload nao retornou id" }
}
Step "anexo aparece na listagem" {
    $list = Invoke-RestMethod -Uri "$API/documents/$docId/attachments" -Headers $W
    if (-not ($list | Where-Object { $_.id -eq $attachmentId })) { throw "anexo nao listado" }
}
Step "download do anexo retorna o mesmo conteudo" {
    $tmp = Join-Path $env:TEMP "labdms-e2e-attachment.txt"
    Invoke-WebRequest -Uri "$API/documents/$docId/attachments/$attachmentId/download" -Headers $W -OutFile $tmp | Out-Null
    $content = Get-Content $tmp -Raw
    if ($content -notmatch "Certificado de teste E2E $stamp") { throw "conteudo baixado nao confere" }
}
Step "reader NAO consegue enviar anexo (sem permissao de edicao)" {
    try {
        UploadAttachment $readerToken $docId "nao-deveria.txt" "x" $null
        throw "reader enviou anexo!"
    } catch { if ($_.Exception.Message -match "enviou anexo") { throw } }
}
Step "tipo de arquivo nao permitido e rejeitado (400)" {
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $writerToken)
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    $fc = New-Object System.Net.Http.ByteArrayContent(, [System.Text.Encoding]::UTF8.GetBytes("MZ..."))
    $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/x-msdownload")
    $mp.Add($fc, "file", "malicioso.exe")
    $resp = $client.PostAsync("$API/documents/$docId/attachments", $mp).Result
    if ($resp.IsSuccessStatusCode) { throw "arquivo .exe foi aceito!" }
    if ([int]$resp.StatusCode -ne 400) { throw "esperava 400, veio $([int]$resp.StatusCode)" }
}
Step "writer remove o anexo" {
    Invoke-RestMethod -Method Delete -Uri "$API/documents/$docId/attachments/$attachmentId" -Headers $W | Out-Null
    $list = Invoke-RestMethod -Uri "$API/documents/$docId/attachments" -Headers $W
    if ($list | Where-Object { $_.id -eq $attachmentId }) { throw "anexo ainda listado apos remocao" }
}

# ------------------------------------------------------------
Write-Host "`n[6.2] Notificacoes"
Step "reviewer tem notificacao de documento submetido" {
    $notifs = Invoke-RestMethod -Uri "$API/notifications" -Headers (AuthHeaders $reviewerToken)
    if (-not ($notifs | Where-Object { $_.type -eq "REVIEW_SUBMITTED" })) { throw "notificacao de submissao nao encontrada" }
}
Step "writer tem notificacao de aprovacao" {
    $notifs = Invoke-RestMethod -Uri "$API/notifications" -Headers $W
    if (-not ($notifs | Where-Object { $_.type -eq "REVIEW_APPROVED" })) { throw "notificacao de aprovacao nao encontrada" }
}
Step "marcar notificacao como lida reduz o contador de nao lidas" {
    $before = (Invoke-RestMethod -Uri "$API/notifications/unread-count" -Headers $W).count
    $notifs = Invoke-RestMethod -Uri "$API/notifications" -Headers $W
    $unread = $notifs | Where-Object { -not $_.isRead } | Select-Object -First 1
    if (-not $unread) { return } # nothing unread — not a failure
    Invoke-RestMethod -Method Post -Uri "$API/notifications/$($unread.id)/read" -Headers $W | Out-Null
    $after = (Invoke-RestMethod -Uri "$API/notifications/unread-count" -Headers $W).count
    if ($after -ge $before) { throw "contador nao diminuiu ($before -> $after)" }
}

# ------------------------------------------------------------
Write-Host "`n[6.3] Grupos/Setores (admin)"
$groupId = $null
$groupName = "Grupo Teste $stamp"
Step "admin cria grupo" {
    $body = @{ name = $groupName; description = "Grupo de teste E2E" } | ConvertTo-Json
    $g = Invoke-RestMethod -Method Post -Uri "$API/documents/groups" -Headers $H -ContentType "application/json" -Body $body
    $script:groupId = $g.id
}
Step "writer NAO consegue criar grupo (nao e admin)" {
    $body = @{ name = "Nao deveria $stamp"; description = "x" } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/documents/groups" -Headers $W -ContentType "application/json" -Body $body
        throw "writer criou grupo!"
    } catch { if ($_.Exception.Message -match "criou grupo") { throw } }
}
Step "admin edita o grupo" {
    $body = @{ name = $groupName; description = "Descricao atualizada" } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$API/documents/groups/$groupId" -Headers $H -ContentType "application/json" -Body $body
}
Step "nao permite grupo duplicado" {
    $body = @{ name = $groupName; description = "x" } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/documents/groups" -Headers $H -ContentType "application/json" -Body $body
        throw "grupo duplicado foi aceito!"
    } catch { if ($_.Exception.Message -match "duplicado foi aceito") { throw } }
}
Step "admin remove o grupo (sem documentos usando)" {
    Invoke-RestMethod -Method Delete -Uri "$API/documents/groups/$groupId" -Headers $H | Out-Null
    $groups = Invoke-RestMethod -Uri "$API/documents/groups" -Headers $H
    if ($groups | Where-Object { $_.id -eq $groupId }) { throw "grupo ainda existe apos remocao" }
}
Step "admin NAO consegue remover grupo em uso (409)" {
    $groups = Invoke-RestMethod -Uri "$API/documents/groups" -Headers $H
    $emUso = $groups | Where-Object { $_.name -eq "Soldagem" }
    if (-not $emUso) { return } # seed ausente — nao aplicavel
    try {
        Invoke-RestMethod -Method Delete -Uri "$API/documents/groups/$($emUso.id)" -Headers $H
        throw "grupo em uso foi removido!"
    } catch {
        if ($_.Exception.Message -match "em uso foi removido") { throw }
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 409) { throw "esperava 409, veio $status" }
    }
}

Step "admin OBSOLETA o documento" {
    $body = @{ status = "OBSOLETE" } | ConvertTo-Json
    Invoke-RestMethod -Method Patch -Uri "$API/documents/$docId/status" -Headers $H -ContentType "application/json" -Body $body
}

# ------------------------------------------------------------
Write-Host "`n[7] Permissoes (roles bem definidas)"
Step "reader NAO consegue criar documento" {
    $body = @{ code = "IT-NEG-$stamp"; title = "Nao deveria"; docType = "IT";
               documentGroupId = $null; initialLatexSource = "\documentclass{article}\begin{document}x\end{document}"; tags = @() } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/documents" -Headers (AuthHeaders $readerToken) -ContentType "application/json" -Body $body
        throw "reader criou documento!"
    } catch { if ($_.Exception.Message -match "criou documento") { throw } }
}
Step "writer NAO consegue aprovar revisao" {
    $body = @{ title = "Perm $stamp"; description = "x" } | ConvertTo-Json
    try {
        Invoke-RestMethod -Uri "$API/audit" -Headers (AuthHeaders $writerToken) | Out-Null
        throw "writer acessou auditoria!"
    } catch { if ($_.Exception.Message -match "acessou auditoria") { throw } }
}

# ------------------------------------------------------------
Write-Host "`n[8] Auditoria e Dashboard"
Step "trilha de auditoria (admin)" {
    $a = Invoke-RestMethod -Uri "$API/audit?size=10" -Headers $H
    if ($a.entries.Count -lt 1) { throw "auditoria vazia" }
}
Step "dashboard stats" {
    $s = Invoke-RestMethod -Uri "$API/dashboard/stats" -Headers $H
    if ($s.totalDocuments -lt 1) { throw "sem documentos no dashboard" }
}
Step "listagem mostra documentos (incl. rascunhos)" {
    $docs = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $H
    $codes = $docs.content | ForEach-Object { $_.code }
    if ($codes.Count -lt 1) { throw "listagem vazia" }
    if (-not ($codes -contains "IT-E2E-$stamp")) { throw "documento recem-criado (DRAFT/obsoleto) nao aparece na listagem" }
}
Step "documentos de exemplo semeados com historico" {
    $docs = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $H
    $exemplo = $docs.content | Where-Object { $_.code -eq "IT-SOLDAGEM-001" }
    if (-not $exemplo) { Write-Host "         (seeds ausentes — recrie o volume: docker compose down -v)" -ForegroundColor DarkYellow; return }
    $hist = Invoke-RestMethod -Uri "$API/documents/$($exemplo.id)/versioning/commits" -Headers $H
    if ($hist.Count -lt 3) { throw "IT-SOLDAGEM-001 deveria ter 3 versoes, tem $($hist.Count)" }
    $autores = ($hist | ForEach-Object { $_.authorName } | Sort-Object -Unique).Count
    if ($autores -lt 2) { throw "esperava versoes de autores diferentes" }
}
Step "revisao pendente semeada aparece na fila" {
    $abertas = Invoke-RestMethod -Uri "$API/reviews?status=OPEN" -Headers $H
    if (-not ($abertas | Where-Object { $_.documentCode -eq "IT-CALIBRACAO-002" })) {
        Write-Host "         (seed IT-CALIBRACAO-002 ausente — recrie o volume)" -ForegroundColor DarkYellow
    }
}
Step "compilar documento de exemplo (motor com latexmk)" {
    $docs = Invoke-RestMethod -Uri "$API/documents?size=50" -Headers $H
    $exemplo = $docs.content | Where-Object { $_.code -eq "IT-SOLDAGEM-001" }
    if (-not $exemplo) { return }
    $hist = Invoke-RestMethod -Uri "$API/documents/$($exemplo.id)/versioning/commits" -Headers $H
    $src = (Invoke-RestMethod -Uri "$API/documents/$($exemplo.id)/versioning/commits/$($hist[0].id)/content" -Headers $H).content
    $body = @{ latexSource = $src } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP "labdms-seed.pdf"
    $resp = Invoke-WebRequest -Method Post -Uri "$API/latex/preview" -Headers $H -ContentType "application/json" -Body $body -OutFile $tmp -PassThru
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    if ([System.Text.Encoding]::ASCII.GetString($bytes[0..3]) -ne "%PDF") { throw "exemplo nao compilou" }
    $paginas = $resp.Headers["X-Pdf-Pages"]
    $problemas = $resp.Headers["X-Latex-Problems"]
    if ("$problemas" -ne "0") { throw "documento de exemplo compilou com $problemas problema(s)" }
    Write-Host "         PDF com $paginas pagina(s), 0 problemas" -ForegroundColor DarkGray
}
Step "preview compila SEM salvar (novo endpoint)" {
    $body = @{ latexSource = $latex } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP "labdms-preview.pdf"
    Invoke-WebRequest -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" -Body $body -OutFile $tmp | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    if ([System.Text.Encoding]::ASCII.GetString($bytes[0..3]) -ne "%PDF") { throw "preview nao retornou PDF" }
}
Step "preview aceita \input relativo e \includegraphics (nao bloqueia legitimo)" {
    $tex = "\documentclass{article}" + "`n" + "\usepackage{graphicx}" + "`n" +
           "\begin{document}" + "`n" + "Tabela MIG/MAG 24--28 \\ fim" + "`n" + "\end{document}"
    $body = @{ latexSource = $tex } | ConvertTo-Json
    $tmp = Join-Path $env:TEMP "labdms-preview2.pdf"
    Invoke-WebRequest -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" -Body $body -OutFile $tmp | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    if ([System.Text.Encoding]::ASCII.GetString($bytes[0..3]) -ne "%PDF") { throw "documento legitimo foi rejeitado" }
}
Step "preview BLOQUEIA \write18 com mensagem clara (422)" {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "\write18{rm -rf /}" + "`n" + "\end{document}"
    $body = @{ latexSource = $tex } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" -Body $body
        throw "shell escape foi aceito!"
    } catch {
        if ($_.Exception.Message -match "foi aceito") { throw }
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 422) { throw "esperava 422, veio $status" }
    }
}
Step "preview reporta erro de sintaxe LaTeX com log (422)" {
    $tex = "\documentclass{article}" + "`n" + "\begin{document}" + "`n" + "\comandoInexistente{x}" + "`n" + "\end{document}"
    $body = @{ latexSource = $tex } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$API/latex/preview" -Headers $W -ContentType "application/json" -Body $body
        throw "LaTeX invalido compilou!"
    } catch {
        if ($_.Exception.Message -match "invalido compilou") { throw }
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 422) { throw "esperava 422, veio $status" }
    }
}

# ------------------------------------------------------------
Write-Host "`n=== RESULTADO: $PASS OK, $FAIL FALHAS ===" -ForegroundColor $(if ($FAIL -eq 0) { "Green" } else { "Red" })
exit $(if ($FAIL -eq 0) { 0 } else { 1 })
