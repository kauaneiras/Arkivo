@echo off
REM Pergunta uma coisa so: o que esta RODANDO tem o codigo das funcionalidades?
REM Olha dentro do container, no arquivo que o navegador realmente baixa.
cd /d "%~dp0"
set LOG=diagnostico.txt

echo ============================================== > "%LOG%"
echo  O QUE ESTA RODANDO TEM AS FUNCIONALIDADES?    >> "%LOG%"
echo  %DATE% %TIME%                                 >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] IMAGEM DO FRONTEND QUE ESTA DE PE >> "%LOG%"
docker ps --filter "name=lab-dms-frontend" --format "   {{.Image}}  criado {{.CreatedAt}}  {{.Status}}" >> "%LOG%" 2>&1
docker inspect lab-dms-frontend --format "   imagem construida em: {{.Created}}" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [2] ARQUIVOS SERVIDOS >> "%LOG%"
docker exec lab-dms-frontend sh -c "ls -la /usr/share/nginx/html/assets/*.js" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [3] AS FUNCIONALIDADES ESTAO NO PACOTE SERVIDO? >> "%LOG%"
echo     (chaves de traducao sobrevivem a minificacao como chaves de objeto) >> "%LOG%"
for %%K in (create.groupRestrictedHint create.manageGroups create.noDeadline privacy.RESTRICTED.hint settings.groups branding.title about.title) do (
  docker exec lab-dms-frontend sh -c "grep -l '%%K' /usr/share/nginx/html/assets/*.js 2>/dev/null | head -1" > tmp.txt 2>&1
  set /p FOUND=<tmp.txt
  call :REPORT "%%K"
)
goto AFTER

:REPORT
setlocal enabledelayedexpansion
set /p F=<tmp.txt
if "!F!"=="" (echo    AUSENTE  %~1 >> "%LOG%") else (echo    presente %~1 >> "%LOG%")
endlocal
goto :eof

:AFTER
echo. >> "%LOG%"
echo [4] O PACOTE E O MESMO QUE O NAVEGADOR BAIXA? >> "%LOG%"
curl -s http://localhost:3000/ -o idx.html
powershell -NoProfile -Command "$h = Get-Content idx.html -Raw; ([regex]::Matches($h, '/assets/[A-Za-z0-9._-]+\.(js|mjs|css)')) | Select-Object -ExpandProperty Value -Unique | ForEach-Object { '   index.html pede: ' + $_ }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] DATA DO CODIGO-FONTE vs DATA DA IMAGEM >> "%LOG%"
powershell -NoProfile -Command "Get-ChildItem -Recurse lab-dms-frontend\src -Include *.tsx,*.ts | Sort-Object LastWriteTime -Descending | Select-Object -First 3 | ForEach-Object { '   fonte: ' + $_.LastWriteTime + '  ' + $_.Name }" >> "%LOG%" 2>&1

del tmp.txt idx.html 2>nul

echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
echo.
echo Pronto. Resultado em diagnostico.txt
timeout /t 8
