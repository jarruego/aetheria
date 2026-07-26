# Backup de Aetheria: base de datos (Postgres) + mundos de Minecraft.
#
# Copia consistente y reproducible, sin parar el servidor:
#   - Postgres: pg_dump comprimido.
#   - Mundos: tar.gz del volumen de cada servidor Paper (via --volumes-from, sin
#     depender del nombre exacto del volumen del proyecto).
# Guarda en backups/ y conserva solo los ULTIMOS N (por defecto 14).
#
# Uso:   ./scripts/backup.ps1            # backup completo
#        ./scripts/backup.ps1 -Keep 30   # conserva 30 en vez de 14
#
# Restaurar la DB:  gzip -dc backups/db-XXXX.sql.gz | docker compose exec -T postgres psql -U aetheria -d aetheria
# Restaurar un mundo: parar el server y desempaquetar el .tgz sobre el volumen.

param(
    [int]$Keep = 14,
    [string]$OutDir = "backups"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root $OutDir
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$pgUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "aetheria" }
$pgDb   = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { "aetheria" }

Write-Host "[backup] Base de datos ($pgDb)..."
$dbFile = Join-Path $dest "db-$stamp.sql.gz"
# El volcado y la compresion ocurren DENTRO del contenedor (asi PowerShell no toca los
# bytes del gzip y no los corrompe); luego se saca con docker cp (binario-seguro).
docker compose exec -T postgres sh -c "pg_dump -U $pgUser $pgDb | gzip -c > /tmp/aetheria-dump.sql.gz"
if ($LASTEXITCODE -ne 0) { throw "pg_dump fallo; ¿esta arrancado el contenedor 'postgres'?" }
$pgCid = (docker compose ps -q postgres).Trim()
docker cp "${pgCid}:/tmp/aetheria-dump.sql.gz" $dbFile
docker compose exec -T postgres rm -f /tmp/aetheria-dump.sql.gz | Out-Null
if (-not (Test-Path $dbFile) -or (Get-Item $dbFile).Length -lt 100) {
    throw "El volcado de la DB salio vacio o no se copio."
}
Write-Host "[backup]   -> $dbFile"

# Mundos: un .tgz por cada contenedor Paper que este corriendo.
$servers = @{ "aetheria-main-1" = "world-main"; "aetheria-lobby-1" = "world-lobby"; "aetheria-creative-1" = "world-creative" }
foreach ($container in $servers.Keys) {
    $running = docker ps --filter "name=$container" --filter "status=running" --format "{{.Names}}"
    if ($running -ne $container) { continue }
    $label = $servers[$container]
    Write-Host "[backup] Mundo $label ($container)..."
    $worldFile = Join-Path $dest "$label-$stamp.tgz"
    # Se empaqueta DENTRO del propio contenedor Paper (ya tiene tar; sin imagenes extra) y
    # se saca con docker cp (binario-seguro). Solo los mundos, no todo /data.
    docker exec $container sh -c "cd /data && tar czf /tmp/aetheria-world.tgz world* 2>/dev/null" | Out-Null
    docker cp "${container}:/tmp/aetheria-world.tgz" $worldFile
    docker exec $container rm -f /tmp/aetheria-world.tgz | Out-Null
    Write-Host "[backup]   -> $worldFile"
}

# Poda: conserva solo los ultimos $Keep de cada tipo (db y cada mundo).
foreach ($pattern in @("db-*.sql.gz", "world-main-*.tgz", "world-lobby-*.tgz", "world-creative-*.tgz")) {
    $old = Get-ChildItem -Path $dest -Filter $pattern | Sort-Object Name -Descending | Select-Object -Skip $Keep
    foreach ($f in $old) { Remove-Item $f.FullName -Force }
}

Write-Host "[backup] Listo. Conservando hasta $Keep copias por tipo en $dest"
