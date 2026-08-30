@echo off
REM Por que gravar a versao falha com 500.
REM
REM Todas as instrucoes SQL do caminho de gravacao foram validadas contra um
REM PostgreSQL limpo e passam. Entao a causa esta no ESTADO desta base. Este
REM script traz esse estado e a linha de erro do Postgres, que e o que falta.
REM
REM Uso:  DIAGNOSTICO-SALVAR.bat            (usa o documento do seu log)
REM       DIAGNOSTICO-SALVAR.bat <id-doc>

cd /d "%~dp0"
set LOG=diagnostico-salvar.txt
set PSQL=docker exec lab-dms-postgres psql -U labdms -d labdms
set DOC=%~1
if "%DOC%"=="" set DOC=20c60969-cc73-4326-a370-4896def9d09b

echo ============================================== > "%LOG%"
echo  POR QUE GRAVAR A VERSAO FALHA                >> "%LOG%"
echo  documento: %DOC%                             >> "%LOG%"
echo  %DATE% %TIME%                                >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [1] A LINHA DE ERRO DO POSTGRES (o que falta saber) >> "%LOG%"
docker compose logs backend --tail 5000 > tmp.txt 2>&1
powershell -NoProfile -Command ^
  "$l = Get-Content tmp.txt;" ^
  "$m = $l | Where-Object { $_ -match 'Database error \[SQLState|PSQLException|ERROR: |ERRO: |violates |duplicate key|null value in column|does not exist|valor duplicado' };" ^
  "if (-not $m) { '   nenhuma encontrada — reconstrua o backend e tente gravar de novo' } else { $m | Select-Object -Last 12 | ForEach-Object { '   ' + ($_ -replace '^\S+\s+\|\s*','').Trim() } }" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [2] O DOCUMENTO >> "%LOG%"
%PSQL% -x -c "SELECT code, title, status, current_commit_id, official_commit_id, (current_commit_id = official_commit_id) AS atual_e_vigente, document_group_id, project_size_bytes, root_file_path FROM documents WHERE id = '%DOC%';" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [3] RAMOS  (head nulo? mais de um default?) >> "%LOG%"
%PSQL% -c "SELECT id, name, is_default, status, head_commit_id FROM branches WHERE document_id = '%DOC%';" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [4] VERSOES >> "%LOG%"
%PSQL% -c "SELECT c.id, c.message, c.content_snapshot_id, c.parent_commit_id, (SELECT COUNT(*) FROM project_files f WHERE f.commit_id=c.id) AS ficheiros, (SELECT COUNT(*) FROM project_files f WHERE f.commit_id=c.id AND f.is_root) AS raizes FROM commits c WHERE c.document_id = '%DOC%' ORDER BY c.created_at;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [5] MAIS DE UMA RAIZ, OU CAMINHOS DUPLICADOS? >> "%LOG%"
echo     (violaria uk_project_files_root / uk_project_files_commit_path na copia) >> "%LOG%"
%PSQL% -c "SELECT commit_id, COUNT(*) FILTER (WHERE is_root) AS raizes, COUNT(*) AS total FROM project_files WHERE commit_id IN (SELECT id FROM commits WHERE document_id='%DOC%') GROUP BY commit_id HAVING COUNT(*) FILTER (WHERE is_root) <> 1;" >> "%LOG%" 2>&1
%PSQL% -c "SELECT commit_id, path, COUNT(*) FROM project_files WHERE commit_id IN (SELECT id FROM commits WHERE document_id='%DOC%') GROUP BY commit_id, path HAVING COUNT(*) > 1;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [6] FICHEIROS DA VERSAO ATUAL >> "%LOG%"
%PSQL% -c "SELECT path, size_bytes, is_text, is_root, blob_sha256 FROM project_files WHERE commit_id = (SELECT COALESCE(current_commit_id, official_commit_id) FROM documents WHERE id='%DOC%') ORDER BY path;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [7] BLOBS REFERENCIADOS QUE JA NAO EXISTEM >> "%LOG%"
echo     (a copia da arvore violaria a chave estrangeira) >> "%LOG%"
%PSQL% -c "SELECT DISTINCT f.blob_sha256 FROM project_files f WHERE f.commit_id IN (SELECT id FROM commits WHERE document_id='%DOC%') AND NOT EXISTS (SELECT 1 FROM file_blobs b WHERE b.sha256 = f.blob_sha256);" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [8] VERSAO DO ESQUEMA >> "%LOG%"
%PSQL% -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;" >> "%LOG%" 2>&1

del tmp.txt 2>nul
echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
type "%LOG%"
echo.
echo Gravado em diagnostico-salvar.txt
timeout /t 25
