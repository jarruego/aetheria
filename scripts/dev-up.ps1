# Arranque de desarrollo local (Windows / PowerShell).
# Uso:  ./scripts/dev-up.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $root ".env"))) {
    Copy-Item (Join-Path $root ".env.example") (Join-Path $root ".env")
    Write-Host "Creado .env desde .env.example. Rellena los secretos antes de usar IA real." -ForegroundColor Yellow
}

# Generar la config de runtime de la red Minecraft (inyecta el secreto de forwarding).
& (Join-Path $PSScriptRoot "gen-mc-config.ps1")

Push-Location $root
try {
    docker compose up -d --build
    Write-Host "Servicios levantados." -ForegroundColor Green
    Write-Host "  API Gateway:      http://localhost:8080/health"
    Write-Host "  AI Orchestrator:  http://localhost:8090/health"
    Write-Host "  Minecraft Java:   localhost:$(if ($env:VELOCITY_PORT) { $env:VELOCITY_PORT } else { '25565' })"
    Write-Host "  Minecraft Bedrock:localhost:$(if ($env:GEYSER_BEDROCK_PORT) { $env:GEYSER_BEDROCK_PORT } else { '19132' })"
}
finally {
    Pop-Location
}
