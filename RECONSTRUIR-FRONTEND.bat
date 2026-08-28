@echo off
REM Reconstroi so o frontend (o backend nao mudou) e roda o teste do navegador.
cd /d "%~dp0"
set LOG=resultado-navegador.txt

echo Reconstruindo o frontend...
docker compose build frontend > build-frontend.txt 2>&1
echo    codigo de saida: %ERRORLEVEL%
docker compose up -d --force-recreate --no-deps frontend >> build-frontend.txt 2>&1
timeout /t 6 /nobreak > nul

call "%~dp0TESTE-NAVEGADOR.bat"
