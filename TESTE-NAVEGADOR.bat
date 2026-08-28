@echo off
REM Testa o caminho que o NAVEGADOR usa: tudo pelo nginx na porta 3000, nao
REM direto na 8080. Se o proxy mexer no PDF, a API passa e a tela falha —
REM que e exatamente o sintoma de "a pre-visualizacao nao funciona".
cd /d "%~dp0"
set LOG=resultado-navegador.txt
set BASE=http://localhost:3000

echo ============================================== > "%LOG%"
echo  LAB DMS - o caminho do navegador (nginx:3000) >> "%LOG%"
echo  %DATE% %TIME%                                 >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] A PAGINA CARREGA? >> "%LOG%"
curl -s -o index.out -w "   http: %%{http_code}  tipo: %%{content_type}" %BASE%/ >> "%LOG%" 2>&1
echo. >> "%LOG%"

echo. >> "%LOG%"
echo [2] O PACOTE JS E O WORKER DO PDF.JS >> "%LOG%"
echo     "Failed to load PDF file" quase sempre e o worker faltando. >> "%LOG%"
powershell -NoProfile -Command "$h = Get-Content index.out -Raw; $m = [regex]::Matches($h, '/assets/[A-Za-z0-9._-]+\.js'); if ($m.Count -eq 0) { '   NENHUM /assets/*.js no index.html -- o frontend nao foi construido' } else { $m | Select-Object -First 3 | ForEach-Object { '   ' + $_.Value } }" >> "%LOG%" 2>&1

REM O worker do pdf.js e um arquivo separado; se ele der 404 o visor nunca abre.
powershell -NoProfile -Command "$h = Get-Content index.out -Raw; $js = ([regex]::Match($h, '/assets/index-[A-Za-z0-9._-]+\.js')).Value; if (-not $js) { $js = ([regex]::Match($h, '/assets/[A-Za-z0-9._-]+\.js')).Value }; $js | Set-Content -NoNewline bundle.txt" 2>nul
set /p BUNDLE=<bundle.txt
echo    pacote principal: %BUNDLE% >> "%LOG%"
curl -s -o bundle.js -w "   pacote -^> http: %%{http_code}  tipo: %%{content_type}" "%BASE%%BUNDLE%" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b = Get-Content bundle.js -Raw; $w = [regex]::Matches($b, '/assets/[A-Za-z0-9._-]*(worker|pdf)[A-Za-z0-9._-]*\.m?js') | Select-Object -ExpandProperty Value -Unique; if ($w) { $w | Select-Object -First 4 | ForEach-Object { '   worker referenciado: ' + $_ } ; ($w | Select-Object -First 1) | Set-Content -NoNewline worker.txt } else { '   nenhum worker de pdf.js referenciado no pacote'; Set-Content -NoNewline worker.txt '' }" >> "%LOG%" 2>&1
set /p WORKER=<worker.txt
if not "%WORKER%"=="" (
  curl -s -o nul -w "%%{http_code} %%{content_type}" "%BASE%%WORKER%" > wtype.txt 2>&1
  REM O TIPO importa tanto quanto o 200: o Chrome recusa arrancar um worker de
  REM modulo cujo script nao venha com MIME de JavaScript. Servido como
  REM application/octet-stream, o visor nunca abre e o erro na tela e
  REM "Failed to load PDF file" — sem nenhuma pista de que a causa foi o MIME.
  powershell -NoProfile -Command "$t = (Get-Content wtype.txt -Raw).Trim(); '   worker -> ' + $t; if ($t -match '^200' -and $t -match 'javascript|ecmascript') { '   MIME DE JAVASCRIPT -- o worker do pdf.js arranca' } else { '   *** FALHA: o worker nao tem MIME de JavaScript. O visor de PDF NAO vai abrir. ***' }" >> "%LOG%" 2>&1
)

echo. >> "%LOG%"
echo [3] LOGIN PELO PROXY >> "%LOG%"
curl -s -X POST %BASE%/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin@email.com\",\"password\":\"123456\"}" -o t.json -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "try { (Get-Content t.json -Raw | ConvertFrom-Json).accessToken | Set-Content -NoNewline t.txt } catch { Set-Content -NoNewline t.txt '' }" 2>nul
set /p TOKEN=<t.txt
if "%TOKEN%"=="" (
  echo    FALHOU. Resposta: >> "%LOG%"
  type t.json >> "%LOG%" 2>&1
  goto FIM
)
echo    token obtido >> "%LOG%"

echo. >> "%LOG%"
echo [4] PRE-VISUALIZACAO AO VIVO PELO PROXY >> "%LOG%"
echo     Com o MESMO cabecalho Accept que o axios manda. >> "%LOG%"
curl -s -X POST %BASE%/api/v1/latex/preview -H "Content-Type: application/json" -H "Accept: application/pdf, application/json" -H "Authorization: Bearer %TOKEN%" -d @scripts\teste-preview.json -o live.out -D live.hdr -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('live.out'); if ($b.Length -gt 4 -and $b[0] -eq 37 -and $b[1] -eq 80 -and $b[2] -eq 68 -and $b[3] -eq 70) { '   PDF INTEIRO PELO PROXY: ' + $b.Length + ' bytes -- OK' } else { '   CHEGOU CORROMPIDO OU NAO E PDF (' + $b.Length + ' bytes).'; '   primeiros bytes: ' + (($b | Select-Object -First 16 | ForEach-Object { $_.ToString('x2') }) -join ' '); (Get-Content live.out -Raw -ErrorAction SilentlyContinue | Select-Object -First 1) }" >> "%LOG%" 2>&1
echo    --- cabecalhos que o navegador recebe --- >> "%LOG%"
powershell -NoProfile -Command "(Get-Content live.hdr) | Where-Object { $_ -match 'HTTP/|Content-Type|Content-Length|Content-Encoding|Transfer-Encoding|X-Pdf|X-Latex|Access-Control-Expose' } | ForEach-Object { '   ' + $_.Trim() }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] O MESMO PEDIDO DIRETO NA 8080, PARA COMPARAR >> "%LOG%"
curl -s -X POST http://localhost:8080/api/v1/latex/preview -H "Content-Type: application/json" -H "Accept: application/pdf, application/json" -H "Authorization: Bearer %TOKEN%" -d @scripts\teste-preview.json -o direto.out -w "   http: %%{http_code}" >> "%LOG%" 2>&1
echo. >> "%LOG%"
powershell -NoProfile -Command "$p=[IO.File]::ReadAllBytes('live.out'); $d=[IO.File]::ReadAllBytes('direto.out'); '   proxy: ' + $p.Length + ' bytes   direto: ' + $d.Length + ' bytes'; if ($p.Length -eq $d.Length -and $p.Length -gt 0) { '   IDENTICOS -- o nginx nao mexe no PDF' } else { '   DIFERENTES -- o nginx esta alterando a resposta. E aqui que a tela quebra.' }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [6] LISTAR DOCUMENTOS PELO PROXY >> "%LOG%"
curl -s -o nul -H "Authorization: Bearer %TOKEN%" -w "   http: %%{http_code}" %BASE%/api/v1/documents >> "%LOG%" 2>&1
echo. >> "%LOG%"

del index.out bundle.js bundle.txt worker.txt wtype.txt t.json t.txt live.out live.hdr direto.out 2>nul

:FIM
echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
echo.
echo Pronto. Resultado em resultado-navegador.txt
timeout /t 8
