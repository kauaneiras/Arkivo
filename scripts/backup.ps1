# ============================================================
# Lab DMS — Backup
# Dumps PostgreSQL (all documents, versions, users, audit trail)
# and archives the attachments volume (external files).
# ============================================================
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\backup.ps1
#
# Output: backups\lab-dms-backup-<timestamp>\
#   - database.sql        (pg_dump, plain SQL — restorable with psql)
#   - attachments.tar.gz  (contents of the attachments Docker volume)
#
# Restore:
#   Database:    Get-Content database.sql | docker exec -i lab-dms-postgres psql -U labdms -d labdms
#   Attachments: docker run --rm -v lab-dms_attachments:/data -v ${PWD}:/backup alpine `
#                    sh -c "cd /data && tar xzf /backup/attachments.tar.gz"

$ErrorActionPreference = "Stop"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = Join-Path $PSScriptRoot "..\backups\lab-dms-backup-$timestamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

Write-Host "Lab DMS backup -> $backupDir" -ForegroundColor Cyan

# --- Database ---
Write-Host "Dumping PostgreSQL..." -ForegroundColor Yellow
$dbFile = Join-Path $backupDir "database.sql"
docker exec lab-dms-postgres pg_dump -U labdms -d labdms --no-owner --no-privileges > $dbFile
if ($LASTEXITCODE -ne 0) { throw "pg_dump failed. Is the stack running (docker compose up -d)?" }
$dbSize = (Get-Item $dbFile).Length / 1KB
Write-Host "  database.sql ($([math]::Round($dbSize, 1)) KB)" -ForegroundColor Green

# --- Attachments volume ---
Write-Host "Archiving attachments volume..." -ForegroundColor Yellow
$attachmentsArchive = Join-Path $backupDir "attachments.tar.gz"
docker run --rm `
    -v lab-dms_attachments:/data `
    -v "${backupDir}:/backup" `
    alpine sh -c "cd /data && tar czf /backup/attachments.tar.gz . 2>/dev/null || true"
if (Test-Path $attachmentsArchive) {
    $attSize = (Get-Item $attachmentsArchive).Length / 1KB
    Write-Host "  attachments.tar.gz ($([math]::Round($attSize, 1)) KB)" -ForegroundColor Green
} else {
    Write-Host "  (no attachments volume found — skipped)" -ForegroundColor DarkGray
}

Write-Host "`nBackup complete: $backupDir" -ForegroundColor Cyan
Write-Host "Keep this folder outside the repo (it contains real user data)." -ForegroundColor Yellow
