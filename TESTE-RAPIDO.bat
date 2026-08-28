@echo off
REM Exercita a API com os contentores JA de pe. Nao constroi nada.
REM Use este para verificar o visualizador depois de um RODAR-TESTE completo.
cd /d "%~dp0"
set LOG=resultado-rapido.txt
set PSQL=docker exec lab-dms-postgres psql -U labdms -d labdms

echo ============================================== > "%LOG%"
echo  LAB DMS - teste rapido da API                 >> "%LOG%"
echo  %DATE% %TIME%                                 >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] SAUDE DO BACKEND >> "%LOG%"
curl -s -o nul -w "   health: %%{http_code}" http://localhost:8080/actuator/health >> "%LOG%" 2>&1
echo. >> "%LOG%"

echo. >> "%LOG%"
echo [2] LOGIN admin@email.com / 123456 >> "%LOG%"
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
echo [3] CRIANDO UM DOCUMENTO COM FONTE LATEX >> "%LOG%"
curl -s -X POST http://localhost:8080/api/v1/documents -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d @scripts\teste-documento.json -o doc.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
type doc.json >> "%LOG%" 2>&1
echo. >> "%LOG%"

for /f "usebackq tokens=*" %%i in (`%PSQL% -t -A -c "SELECT current_commit_id FROM documents WHERE title = 'Teste automatico do visualizador' ORDER BY created_at DESC LIMIT 1;"`) do set COMMIT=%%i
echo    commit atual: %COMMIT% >> "%LOG%"
if "%COMMIT%"=="" (
  echo    NAO HA COMMIT - a criacao falhou. >> "%LOG%"
  goto LOGS
)

echo. >> "%LOG%"
echo [4] PRE-VISUALIZACAO DO PROJETO  ^(o que o editor chama em Compilar^) >> "%LOG%"
curl -s -X POST "http://localhost:8080/api/v1/latex/preview/project/%COMMIT%" -H "Content-Type: application/json" -H "Accept: application/pdf, application/json" -H "Authorization: Bearer %TOKEN%" -d "{}" -o prev.out -D prev.hdr -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('prev.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80 -and $b[2] -eq 68 -and $b[3] -eq 70) { '   PDF VALIDO: ' + $b.Length + ' bytes -- PRE-VISUALIZACAO OK' } else { '   NAO E UM PDF (' + $b.Length + ' bytes):'; (Get-Content prev.out -Raw -ErrorAction SilentlyContinue) }" >> "%LOG%" 2>&1
echo    --- cabecalhos --- >> "%LOG%"
type prev.hdr >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [4b] PRE-VISUALIZACAO AO VIVO  ^(POST /latex/preview^) >> "%LOG%"
echo      E o caminho que o editor usa num documento de ficheiro unico. >> "%LOG%"
curl -s -X POST "http://localhost:8080/api/v1/latex/preview" -H "Content-Type: application/json" -H "Accept: application/pdf, application/json" -H "Authorization: Bearer %TOKEN%" -d @scripts\teste-preview.json -o live.out -D live.hdr -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('live.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80 -and $b[2] -eq 68 -and $b[3] -eq 70) { '   PDF VALIDO: ' + $b.Length + ' bytes -- PRE-VISUALIZACAO AO VIVO OK' } else { '   NAO E UM PDF (' + $b.Length + ' bytes):'; (Get-Content live.out -Raw -ErrorAction SilentlyContinue) }" >> "%LOG%" 2>&1
powershell -NoProfile -Command "(Get-Content live.hdr) | Where-Object { $_ -match 'Content-Type|X-Pdf-Pages|X-Latex' } | ForEach-Object { '   ' + $_ }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] COMPILAR E GRAVAR >> "%LOG%"
curl -s -X POST "http://localhost:8080/api/v1/latex/compile/%COMMIT%" -H "Authorization: Bearer %TOKEN%" -o comp.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
REM So o veredito: o log do latexmk tem centenas de KB e afogaria o ficheiro.
powershell -NoProfile -Command "try { $c = Get-Content comp.json -Raw | ConvertFrom-Json; '   status: ' + $c.status + '  hasPdf: ' + $c.hasPdf; $e = ($c.compilationLog -split \"`n\" | Where-Object { $_ -match '^!|Error|Emergency stop' } | Select-Object -First 5); if ($e) { '   problemas no log:'; $e | ForEach-Object { '     ' + $_ } } else { '   nenhum erro no log' } } catch { '   resposta: ' + (Get-Content comp.json -Raw) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [6] BUSCAR O PDF GRAVADO >> "%LOG%"
curl -s "http://localhost:8080/api/v1/latex/pdf/%COMMIT%" -H "Accept: application/pdf" -H "Authorization: Bearer %TOKEN%" -o pdf.out -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('pdf.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80) { '   PDF servido: ' + $b.Length + ' bytes -- OK' } else { '   FALHOU (' + $b.Length + ' bytes): ' + (Get-Content pdf.out -Raw -ErrorAction SilentlyContinue) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [7] LISTAR DOCUMENTOS >> "%LOG%"
curl -s "http://localhost:8080/api/v1/documents" -H "Authorization: Bearer %TOKEN%" -o list.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "try { $r = Get-Content list.json -Raw | ConvertFrom-Json; '   documentos devolvidos: ' + $r.content.Count } catch { '   resposta: ' + (Get-Content list.json -Raw) }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [8] LOGIN DOS OUTROS UTILIZADORES >> "%LOG%"
for %%U in (writer reviewer member reader) do (
  curl -s -o nul -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"%%U@email.com\",\"password\":\"123456\"}" -w "   %%U@email.com -^> %%{http_code}" >> "%LOG%" 2>&1
  echo. >> "%LOG%"
)

echo. >> "%LOG%"
echo [9] LIMPANDO O DOCUMENTO DE TESTE >> "%LOG%"
%PSQL% -c "TRUNCATE TABLE document_access, document_acknowledgements, document_attachments, project_files, compiled_pdfs, reviews, review_requests, commits, content_snapshots, branches, documents, notifications, audit_log, document_versions RESTART IDENTITY CASCADE;" >> "%LOG%" 2>&1
%PSQL% -c "SELECT (SELECT COUNT(*) FROM documents) AS documentos, (SELECT COUNT(*) FROM users) AS usuarios;" >> "%LOG%" 2>&1

:LOGS
echo. >> "%LOG%"
echo [10] LOGS DO BACKEND >> "%LOG%"
docker compose logs backend --tail 60 >> "%LOG%" 2>&1

del t.json t.txt doc.json prev.out prev.hdr live.out live.hdr comp.json pdf.out list.json 2>nul

echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
echo.
echo Pronto. Resultado em resultado-rapido.txt
timeout /t 8
