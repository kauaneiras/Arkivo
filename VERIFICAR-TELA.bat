@echo off
REM Reconstroi o frontend e PROVA que o que esta sendo servido contem as
REM funcionalidades e que o navegador nao vai reaproveitar codigo velho.
cd /d "%~dp0"
set LOG=verificacao-tela.txt

echo ============================================== > "%LOG%"
echo  LAB DMS - o que a TELA vai receber            >> "%LOG%"
echo  %DATE% %TIME%                                 >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] RECONSTRUINDO O FRONTEND >> "%LOG%"
docker compose build frontend > build-frontend.txt 2>&1
echo    codigo de saida: %ERRORLEVEL% >> "%LOG%"
findstr /C:"nginx: configuration file" /C:"ERROR" /C:"error" build-frontend.txt >> "%LOG%" 2>&1
docker compose up -d --force-recreate --no-deps frontend >> build-frontend.txt 2>&1
timeout /t 6 /nobreak > nul

echo. >> "%LOG%"
echo [2] AS 3 FUNCIONALIDADES ESTAO NO PACOTE SERVIDO? >> "%LOG%"
echo     Chaves de traducao sobrevivem a minificacao como chaves de objeto. >> "%LOG%"
for %%K in (create.manageGroups create.groupRestrictedHint create.noDeadline privacy.RESTRICTED.hint settings.sectionGroups editor.viewerFellBack) do (
  docker exec lab-dms-frontend sh -c "grep -q '%%K' /usr/share/nginx/html/assets/*.js && echo presente || echo AUSENTE" > tmp.txt 2>&1
  set /p R=<tmp.txt
  call :DIZ "%%K"
)
goto DEPOIS

:DIZ
setlocal enabledelayedexpansion
set /p R=<tmp.txt
echo    !R!  %~1 >> "%LOG%"
endlocal
goto :eof

:DEPOIS
echo. >> "%LOG%"
echo [3] O NAVEGADOR VAI PEGAR CODIGO NOVO? >> "%LOG%"
echo     index.html tem de vir com no-store. Sem isso, o navegador reusa o >> "%LOG%"
echo     pacote de ontem e toda entrega parece nao ter sido feita. >> "%LOG%"
curl -s -D h1.txt -o idx.html http://localhost:3000/ > nul 2>&1
powershell -NoProfile -Command "$cc = (Get-Content h1.txt) | Where-Object { $_ -match '^Cache-Control' }; if ($cc.Count -eq 0) { '   *** FALHA: index.html sem Cache-Control -- o navegador vai reusar o codigo velho ***' } elseif ($cc.Count -gt 1) { '   AVISO: ' + $cc.Count + ' cabecalhos Cache-Control (ambiguo):'; $cc | ForEach-Object { '     ' + $_.Trim() } } elseif ($cc[0] -match 'no-store') { '   OK  ' + $cc[0].Trim() } else { '   *** FALHA: ' + $cc[0].Trim() + ' -- precisa de no-store ***' }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [4] O WORKER DO PDF.JS TEM MIME DE JAVASCRIPT? >> "%LOG%"
powershell -NoProfile -Command "$h = Get-Content idx.html -Raw; $js = ([regex]::Match($h,'/assets/index-[A-Za-z0-9._-]+\.js')).Value; $js | Set-Content -NoNewline b.txt" 2>nul
set /p BUNDLE=<b.txt
curl -s -o bundle.js "http://localhost:3000%BUNDLE%" > nul 2>&1
powershell -NoProfile -Command "$b = Get-Content bundle.js -Raw; $w = ([regex]::Match($b,'/assets/[A-Za-z0-9._-]*pdf\.worker[A-Za-z0-9._-]*\.mjs')).Value; if (-not $w) { $w = ([regex]::Match($b,'/assets/[A-Za-z0-9._-]+\.mjs')).Value }; $w | Set-Content -NoNewline w.txt" 2>nul
set /p WORKER=<w.txt
curl -s -o nul -w "%%{http_code} %%{content_type}" "http://localhost:3000%WORKER%" > wt.txt 2>&1
powershell -NoProfile -Command "$t=(Get-Content wt.txt -Raw).Trim(); '   worker ' + $env:WORKER; '   -> ' + $t; if ($t -match '^200' -and $t -match 'javascript|ecmascript') { '   OK -- o visor de PDF consegue arrancar' } else { '   *** FALHA: sem MIME de JavaScript. O Chrome recusa o worker e o PDF nao abre. ***' }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] OS ASSETS COM HASH PODEM SER GUARDADOS? >> "%LOG%"
curl -s -D h2.txt -o nul "http://localhost:3000%BUNDLE%" > nul 2>&1
powershell -NoProfile -Command "$cc = (Get-Content h2.txt) | Where-Object { $_ -match '^Cache-Control' }; if ($cc.Count -eq 1 -and $cc[0] -match 'immutable') { '   OK  ' + $cc[0].Trim() } else { '   ' + ($cc -join ' | ') }" >> "%LOG%" 2>&1

del tmp.txt idx.html bundle.js b.txt w.txt wt.txt h1.txt h2.txt 2>nul

echo. >> "%LOG%"
echo ================================================== >> "%LOG%"
echo  Se tudo acima estiver OK, abra http://localhost:3000 >> "%LOG%"
echo  com um refresh NORMAL (F5). O CTRL+SHIFT+R deixou de >> "%LOG%"
echo  ser necessario -- era remendo para o cabecalho que faltava. >> "%LOG%"
echo ================================================== >> "%LOG%"
echo.
echo Pronto. Resultado em verificacao-tela.txt
timeout /t 8
