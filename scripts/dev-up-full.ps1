# Arranque LOCAL COMPLETO (Windows): lobby + main + End + mas memoria.
# Genera la config en modo 'full' y levanta con el override docker-compose.full.yml.
# Uso:  ./scripts/dev-up-full.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $root ".env"))) {
    Copy-Item (Join-Path $root ".env.example") (Join-Path $root ".env")
    Write-Host "Creado .env desde .env.example. Rellena los secretos antes de usar IA real." -ForegroundColor Yellow
}

# Config de Minecraft en modo FULL (lobby en velocity.toml, End activado).
& (Join-Path $PSScriptRoot "gen-mc-config.ps1") -Mode full

Push-Location $root
try {
    docker compose -f docker-compose.yml -f docker-compose.full.yml up -d --build
    Write-Host "Sistema COMPLETO levantado (lobby + main + End)." -ForegroundColor Green
    Write-Host "  API Gateway:      http://localhost:8080/health"
    Write-Host "  AI Orchestrator:  http://localhost:8090/health"
    Write-Host "  World-State:      http://localhost:8070/health"
    Write-Host "  Minecraft Java:   localhost:$(if ($env:VELOCITY_PORT) { $env:VELOCITY_PORT } else { '25565' })  (entras al Lobby)"
    Write-Host "  Bedrock:          localhost:$(if ($env:GEYSER_BEDROCK_PORT) { $env:GEYSER_BEDROCK_PORT } else { '19132' })"
    Write-Host "  En el juego:  /server main   |   /server lobby"
}
finally {
    Pop-Location
}
