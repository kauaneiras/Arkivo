@echo off
REM Extrai a CAUSA dos erros do backend, sem o rasto de pilha.
REM
REM Um erro de base de dados produz ~180 linhas de pilha, e a frase que importa
REM ("duplicate key", "null value in column X", o nome do indice violado) fica
REM na primeira, que quase nunca e a que se copia. Este script mostra so essas.
cd /d "%~dp0"
set LOG=erro-do-backend.txt

echo ============================================== > "%LOG%"
echo  CAUSAS DOS ERROS RECENTES DO BACKEND         >> "%LOG%"
echo  %DATE% %TIME%                                >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] EXCECOES E A SUA PRIMEIRA LINHA (a causa) >> "%LOG%"
docker compose logs backend --tail 4000 > tmp-full.txt 2>&1
powershell -NoProfile -Command ^
  "$l = Get-Content tmp-full.txt;" ^
  "$hits = 0..($l.Count-1) | Where-Object { $l[$_] -match 'Exception|ERROR|SQLState|violates|duplicate key|null value in column|does not exist' -and $l[$_] -notmatch '^\s+at |common frames omitted' };" ^
  "if (-not $hits) { '   nenhum erro recente' } else { $hits | Select-Object -Last 40 | ForEach-Object { '   ' + ($l[$_] -replace '^\S+\s+\|\s*','').Trim() } }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [2] SO AS MENSAGENS DO POSTGRES >> "%LOG%"
powershell -NoProfile -Command ^
  "$l = Get-Content tmp-full.txt;" ^
  "$m = $l | Where-Object { $_ -match 'PSQLException|ERROR: |violates (foreign key|unique|check|not-null)|duplicate key|null value in column' };" ^
  "if (-not $m) { '   nenhuma' } else { $m | Select-Object -Last 20 | ForEach-Object { '   ' + ($_ -replace '^\S+\s+\|\s*','').Trim() } }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [3] AS 3 LINHAS ANTES DE CADA 'at org.postgresql' >> "%LOG%"
echo     (e onde a causa costuma estar) >> "%LOG%"
powershell -NoProfile -Command ^
  "$l = Get-Content tmp-full.txt;" ^
  "$idx = 0..($l.Count-1) | Where-Object { $l[$_] -match 'at org\.postgresql' } | Select-Object -First 1;" ^
  "if ($null -eq $idx) { '   nenhum' } else { ([Math]::Max(0,$idx-8))..$idx | ForEach-Object { '   ' + ($l[$_] -replace '^\S+\s+\|\s*','').Trim() } }" >> "%LOG%" 2>&1

del tmp-full.txt 2>nul
echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
type "%LOG%"
echo.
echo Gravado em erro-do-backend.txt
timeout /t 20
