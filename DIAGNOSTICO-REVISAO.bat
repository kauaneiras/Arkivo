@echo off
REM Por que a aprovacao foi recusada.
REM
REM O servidor tinha duas causas diferentes com a MESMA mensagem, entao o log
REM nao permitia distinguir. Este script pergunta ao banco, verificacao por
REM verificacao, na mesma ordem em que o codigo as faz.
REM
REM Uso:  DIAGNOSTICO-REVISAO.bat <id-do-pedido-de-revisao> <email-de-quem-aprova>
REM Ex.:  DIAGNOSTICO-REVISAO.bat f15b19e8-32e7-4d2b-b25b-8027c091b915 reviewer@email.com

cd /d "%~dp0"
set LOG=diagnostico-revisao.txt
set PSQL=docker exec lab-dms-postgres psql -U labdms -d labdms

if "%~1"=="" (
  echo Falta o id do pedido de revisao.
  echo Uso: DIAGNOSTICO-REVISAO.bat ^<id-do-pedido^> ^<email^>
  timeout /t 10
  exit /b 1
)
if "%~2"=="" (
  echo Falta o e-mail de quem tentou aprovar.
  timeout /t 10
  exit /b 1
)

echo ============================================== > "%LOG%"
echo  POR QUE A APROVACAO FOI RECUSADA             >> "%LOG%"
echo  pedido: %~1                                  >> "%LOG%"
echo  usuario: %~2                                 >> "%LOG%"
echo  %DATE% %TIME%                                >> "%LOG%"
echo ============================================== >> "%LOG%"

echo. >> "%LOG%"
echo [0] O PEDIDO E O DOCUMENTO >> "%LOG%"
%PSQL% -c "SELECT rr.id AS pedido, d.code AS documento, d.title, d.status, d.document_group_id AS setor_do_doc, g.name AS setor, d.created_by AS autor_id, ua.email AS autor, d.assigned_reviewer_id AS revisor_designado_id, ur2.email AS revisor_designado FROM review_requests rr JOIN documents d ON d.id = rr.document_id LEFT JOIN document_groups g ON g.id = d.document_group_id LEFT JOIN users ua ON ua.id = d.created_by LEFT JOIN users ur2 ON ur2.id = d.assigned_reviewer_id WHERE rr.id = '%~1';" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [1] A CONTA ESTA ATIVA? >> "%LOG%"
%PSQL% -c "SELECT email, status FROM users WHERE email = '%~2';" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [2] QUE PERMISSOES ESTA CONTA TEM? >> "%LOG%"
echo     (e a mesma consulta que o servidor faz) >> "%LOG%"
%PSQL% -c "SELECT p.code FROM permissions p JOIN role_permissions rp ON p.id = rp.permission_id JOIN user_roles ur ON rp.role_id = ur.role_id JOIN users u ON u.id = ur.user_id WHERE u.email = '%~2' UNION SELECT r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id JOIN users u ON u.id = ur.user_id WHERE u.email = '%~2' ORDER BY 1;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [3] O VEREDITO, verificacao por verificacao >> "%LOG%"
%PSQL% -c "WITH me AS (SELECT id, status FROM users WHERE email = '%~2'), doc AS (SELECT d.* FROM review_requests rr JOIN documents d ON d.id = rr.document_id WHERE rr.id = '%~1') SELECT CASE WHEN (SELECT status FROM me) <> 'ACTIVE' THEN 'REPROVA 1: a conta nao esta ativa' ELSE 'ok 1: conta ativa' END AS passo1, CASE WHEN NOT EXISTS (SELECT 1 FROM permissions p JOIN role_permissions rp ON p.id=rp.permission_id JOIN user_roles ur ON rp.role_id=ur.role_id WHERE ur.user_id=(SELECT id FROM me) AND p.code='REVIEW_APPROVE') THEN 'REPROVA 2: nao tem REVIEW_APPROVE' ELSE 'ok 2: tem REVIEW_APPROVE' END AS passo2, CASE WHEN (SELECT document_group_id FROM doc) IS NULL THEN 'ok 3: documento sem setor, nao restringe' WHEN EXISTS (SELECT 1 FROM permissions p JOIN role_permissions rp ON p.id=rp.permission_id JOIN user_roles ur ON rp.role_id=ur.role_id WHERE ur.user_id=(SELECT id FROM me) AND p.code='DOCUMENT_EDIT_ALL') THEN 'ok 3: e administrador, o setor nao trava' WHEN NOT EXISTS (SELECT 1 FROM document_group_members WHERE user_id=(SELECT id FROM me) AND group_id=(SELECT document_group_id FROM doc)) THEN 'REPROVA 3: NAO pertence ao setor do documento' ELSE 'ok 3: pertence ao setor' END AS passo3, CASE WHEN (SELECT id FROM me) = (SELECT created_by FROM doc) THEN 'REPROVA 4: e o autor do documento' ELSE 'ok 4: nao e o autor' END AS passo4, CASE WHEN (SELECT assigned_reviewer_id FROM doc) IS NOT NULL AND (SELECT assigned_reviewer_id FROM doc) <> (SELECT id FROM me) AND NOT EXISTS (SELECT 1 FROM permissions p JOIN role_permissions rp ON p.id=rp.permission_id JOIN user_roles ur ON rp.role_id=ur.role_id WHERE ur.user_id=(SELECT id FROM me) AND p.code='DOCUMENT_EDIT_ALL') THEN 'REPROVA 5: ha outro revisor designado' ELSE 'ok 5' END AS passo5;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [4] SE FOR O SETOR: quem esta nele hoje >> "%LOG%"
%PSQL% -c "SELECT g.name AS setor, u.email, u.status FROM review_requests rr JOIN documents d ON d.id=rr.document_id JOIN document_groups g ON g.id=d.document_group_id LEFT JOIN document_group_members m ON m.group_id=g.id LEFT JOIN users u ON u.id=m.user_id WHERE rr.id='%~1' ORDER BY u.email;" >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo ================== FIM ================== >> "%LOG%"
type "%LOG%"
echo.
echo Resultado tambem gravado em diagnostico-revisao.txt
timeout /t 20
