@echo off
REM Constroi, sobe e EXERCITA o sistema. Grava tudo em resultado-teste.txt,
REM que o Claude le pela pasta partilhada. Duplo-clique neste ficheiro.
cd /d "%~dp0"
set LOG=resultado-teste.txt
set PSQL=docker exec lab-dms-postgres psql -U labdms -d labdms

echo ================================================== > "%LOG%"
echo  LAB DMS - teste de ponta a ponta                  >> "%LOG%"
echo  %DATE% %TIME%                                     >> "%LOG%"
echo ================================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] DOCKER >> "%LOG%"
docker version --format "{{.Server.Version}}" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [2] CONSTRUINDO O FRONTEND >> "%LOG%"
docker compose build frontend >> "%LOG%" 2>&1
echo    codigo de saida: %ERRORLEVEL% >> "%LOG%"

echo. >> "%LOG%"
echo [3] CONSTRUINDO O BACKEND >> "%LOG%"
docker compose build backend >> "%LOG%" 2>&1
set BACKEND_RC=%ERRORLEVEL%
echo    codigo de saida: %BACKEND_RC% >> "%LOG%"

if not "%BACKEND_RC%"=="0" (
  echo. >> "%LOG%"
  echo    BACKEND NAO CONSTRUIU. Subindo so o frontend. >> "%LOG%"
  docker compose up -d --force-recreate --no-deps frontend >> "%LOG%" 2>&1
  goto FIM
)

echo. >> "%LOG%"
echo [4] SUBINDO TUDO >> "%LOG%"
docker compose up -d --force-recreate >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] ESPERANDO O BACKEND >> "%LOG%"
timeout /t 50 /nobreak > nul
curl -s -o nul -w "   health: %%{http_code}" http://localhost:8080/actuator/health >> "%LOG%" 2>&1
echo. >> "%LOG%"

echo. >> "%LOG%"
echo [6] MIGRACOES (esperado: 14 no topo) >> "%LOG%"
%PSQL% -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [7] UTILIZADORES >> "%LOG%"
%PSQL% -c "SELECT email, status, length(password_hash) AS tam_hash FROM users ORDER BY email;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [8] BASE LIMPA? (esperado: documentos=0 versoes=0 auditoria=0 usuarios=5) >> "%LOG%"
%PSQL% -c "SELECT (SELECT COUNT(*) FROM documents) AS documentos, (SELECT COUNT(*) FROM commits) AS versoes, (SELECT COUNT(*) FROM audit_log) AS auditoria, (SELECT COUNT(*) FROM users) AS usuarios;" >> "%LOG%" 2>&1

REM ---------------------------------------------------------------
REM  Daqui para baixo o teste deixa de olhar para o arranque e passa
REM  a USAR o sistema: entrar, criar um documento, compilar o LaTeX e
REM  buscar o PDF. E este trecho que valida o visualizador.
REM ---------------------------------------------------------------

echo. >> "%LOG%"
echo [9] LOGIN admin@email.com / 123456 >> "%LOG%"
REM O campo chama-se "username" (e o username destas contas E o e-mail).
curl -s -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin@email.com\",\"password\":\"123456\"}" -o t.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "try { (Get-Content t.json -Raw | ConvertFrom-Json).accessToken | Set-Content -NoNewline t.txt } catch { Set-Content -NoNewline t.txt '' }" 2>nul
set /p TOKEN=<t.txt
if "%TOKEN%"=="" (
  echo    FALHOU O LOGIN. Resposta: >> "%LOG%"
  type t.json >> "%LOG%" 2>&1
  goto LOGS
)
echo    token obtido >> "%LOG%"

echo. >> "%LOG%"
echo [10] CRIANDO UM DOCUMENTO COM FONTE LATEX >> "%LOG%"
curl -s -X POST http://localhost:8080/api/v1/documents -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d @scripts\teste-documento.json -o doc.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
type doc.json >> "%LOG%" 2>&1
echo. >> "%LOG%"

REM O commit e o que os endpoints de LaTeX recebem, nao o documento.
REM Vem da base para nao depender do formato exato do JSON de resposta.
for /f "usebackq tokens=*" %%i in (`%PSQL% -t -A -c "SELECT current_commit_id FROM documents WHERE title = 'Teste automatico do visualizador' ORDER BY created_at DESC LIMIT 1;"`) do set COMMIT=%%i
echo    commit atual: %COMMIT% >> "%LOG%"
if "%COMMIT%"=="" (
  echo    NAO HA COMMIT - a criacao do documento falhou. >> "%LOG%"
  goto LOGS
)

echo. >> "%LOG%"
echo [11] PRE-VISUALIZACAO DO PROJETO  ^(POST /latex/preview/project/%%commit%%^) >> "%LOG%"
echo      E EXATAMENTE o que o editor chama ao carregar em Compilar. >> "%LOG%"
curl -s -X POST "http://localhost:8080/api/v1/latex/preview/project/%COMMIT%" -H "Content-Type: application/json" -H "Accept: application/pdf, application/json" -H "Authorization: Bearer %TOKEN%" -d "{}" -o prev.out -D prev.hdr -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('prev.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80 -and $b[2] -eq 68 -and $b[3] -eq 70) { '   PDF VALIDO: ' + $b.Length + ' bytes -- PRE-VISUALIZACAO OK' } else { '   NAO E UM PDF (' + $b.Length + ' bytes). Conteudo:'; (Get-Content prev.out -Raw -ErrorAction SilentlyContinue) }" >> "%LOG%" 2>&1
echo    --- cabecalhos --- >> "%LOG%"
type prev.hdr >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [12] COMPILAR E GRAVAR  ^(POST /latex/compile/%%commit%%^) >> "%LOG%"
curl -s -X POST "http://localhost:8080/api/v1/latex/compile/%COMMIT%" -H "Authorization: Bearer %TOKEN%" -o comp.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
REM So o veredito: o log do latexmk tem centenas de KB e afogaria o ficheiro.
powershell -NoProfile -Command "try { $c = Get-Content comp.json -Raw | ConvertFrom-Json; '   status: ' + $c.status + '  hasPdf: ' + $c.hasPdf; $e = ($c.compilationLog -split \"`n\" | Where-Object { $_ -match '^!|Error|Emergency stop' } | Select-Object -First 5); if ($e) { '   problemas no log:'; $e | ForEach-Object { '     ' + $_ } } else { '   nenhum erro no log' } } catch { '   resposta: ' + (Get-Content comp.json -Raw) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [13] BUSCAR O PDF GRAVADO  ^(GET /latex/pdf/%%commit%%^) >> "%LOG%"
curl -s "http://localhost:8080/api/v1/latex/pdf/%COMMIT%" -H "Accept: application/pdf" -H "Authorization: Bearer %TOKEN%" -o pdf.out -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('pdf.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80) { '   PDF servido: ' + $b.Length + ' bytes -- OK' } else { '   FALHOU (' + $b.Length + ' bytes): ' + (Get-Content pdf.out -Raw -ErrorAction SilentlyContinue) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [14] LISTAR DOCUMENTOS  ^(a chamada que dava 500^) >> "%LOG%"
curl -s "http://localhost:8080/api/v1/documents" -H "Authorization: Bearer %TOKEN%" -o list.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "try { $r = Get-Content list.json -Raw | ConvertFrom-Json; '   documentos devolvidos: ' + $r.content.Count } catch { '   resposta: ' + (Get-Content list.json -Raw) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [15] LOGIN DOS OUTROS UTILIZADORES >> "%LOG%"
for %%U in (writer reviewer member reader) do (
  curl -s -o nul -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"%%U@email.com\",\"password\":\"123456\"}" -w "   %%U@email.com -> %%{http_code}" >> "%LOG%" 2>&1
  echo. >> "%LOG%"
)

echo. >> "%LOG%"
echo [16] REMOVENDO O DOCUMENTO DE TESTE >> "%LOG%"
echo      Para que executar isto outra vez continue a mostrar a base limpa. >> "%LOG%"
REM Um DELETE simples em documents esbarraria na chave estrangeira circular
REM (documents.official_commit_id -^> commits, commits.document_id -^> documents).
REM Por isso limpamos o conjunto todo, na mesma ordem ja validada na migracao.
%PSQL% -c "TRUNCATE TABLE document_access, document_acknowledgements, document_attachments, project_files, compiled_pdfs, reviews, review_requests, commits, content_snapshots, branches, documents, notifications, audit_log, document_versions RESTART IDENTITY CASCADE;" >> "%LOG%" 2>&1
%PSQL% -c "SELECT (SELECT COUNT(*) FROM documents) AS documentos, (SELECT COUNT(*) FROM users) AS usuarios;" >> "%LOG%" 2>&1

:LOGS
echo. >> "%LOG%"
echo [17] ERROS DO BACKEND >> "%LOG%"
docker compose logs backend --tail 80 >> "%LOG%" 2>&1

del t.json t.txt doc.json prev.out prev.hdr comp.json pdf.out list.json 2>nul

:FIM
echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
echo.
echo Pronto. Resultado gravado em resultado-teste.txt
echo Pode fechar esta janela.
timeout /t 10
