# Arranque de desarrollo local (Windows / PowerShell).
# Uso:  ./scripts/dev-up.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $root ".env"))) {
    Copy-Item (Join-Path $root ".env.example") (Join-Path $root ".env")
    Write-Host "Creado .env desde .env.example. Rellena los secretos antes de usar IA real." -ForegroundColor Yellow
}

Push-Location $root
try {
    docker compose up -d --build
    Write-Host "Servicios levantados." -ForegroundColor Green
    Write-Host "  API Gateway:      http://localhost:8080/health"
    Write-Host "  AI Orchestrator:  http://localhost:8090/health"
}
finally {
    Pop-Location
}
