# Demonstrates Phase 1 filtering against a RUNNING GitGate server (start it with run-server.ps1).
# Pushes one mixed repo as admin, then clones the SAME url as admin / alice / bob and
# shows that each user receives a different set of files, then proves a hidden blob
# cannot be fetched by its SHA.
$ErrorActionPreference = "Continue"
$env:GIT_TERMINAL_PROMPT = "0"   # fail instead of prompting on bad credentials

$work = Join-Path $env:TEMP "gitgate-demo"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null

Write-Host "`n=== 1. Build a mixed repo and push it as admin ===" -ForegroundColor Cyan
Set-Location $work
git init -q -b main src
Set-Location "$work\src"
git config user.email demo@example.com; git config user.name Admin
New-Item -ItemType Directory frontend, backend | Out-Null
"frontend code"   | Out-File -Encoding ascii frontend\app.js
"backend code"    | Out-File -Encoding ascii backend\server.js
"shared readme"   | Out-File -Encoding ascii README.md
"TOP SECRET KEYS" | Out-File -Encoding ascii TOPSECRET.txt
git add -A; git commit -q -m "initial mixed repo"
git remote add origin "http://admin:admin123@localhost:8080/app.git"
git push origin main
if ($LASTEXITCODE -ne 0) { Write-Host "Push failed - is the server running?" -ForegroundColor Red; return }

function Clone-As($user, $pass, $dir) {
    Set-Location $work
    git clone -q "http://${user}:${pass}@localhost:8080/app.git" $dir
    Write-Host "`n--- $user sees: ---" -ForegroundColor Yellow
    git -C "$work\$dir" ls-files
}

Write-Host "`n=== 2. Clone the same URL as three different users ===" -ForegroundColor Cyan
Clone-As admin admin123 c-admin
Clone-As alice alice123 c-alice
Clone-As bob   bob123   c-bob

Write-Host "`n=== 3. Security proof: can alice fetch a hidden blob by its SHA? ===" -ForegroundColor Cyan
$backendSha = (git -C "$work\c-admin" rev-parse "HEAD:backend/server.js").Trim()
Write-Host "backend/server.js real blob = $backendSha"
$result = git -C "$work\c-alice" cat-file -t $backendSha 2>&1
Write-Host "alice tries to read it -> $result" -ForegroundColor Green

Write-Host "`nDemo working tree: $work" -ForegroundColor DarkGray
Set-Location $work
