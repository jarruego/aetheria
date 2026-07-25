# Genera la configuracion de runtime de la red Minecraft desde las plantillas.
# Inyecta el secreto de forwarding (desde .env) en archivos que quedan FUERA de git.
#
#   ./scripts/gen-mc-config.ps1            # modo 'lean' (solo main, sin End) = cloud/defecto
#   ./scripts/gen-mc-config.ps1 -Mode full # 'full' (lobby + main + End) = local completo
param(
    [ValidateSet('lean', 'full')]
    [string]$Mode = 'lean'
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw ".env no existe. Copia .env.example a .env primero." }

# Leer VELOCITY_FORWARDING_SECRET de .env
$line = Get-Content $envFile | Where-Object { $_ -match '^\s*VELOCITY_FORWARDING_SECRET\s*=' } | Select-Object -First 1
$secret = ($line -replace '^\s*VELOCITY_FORWARDING_SECRET\s*=\s*', '').Trim()
if (-not $secret) { throw "VELOCITY_FORWARDING_SECRET no esta definido en .env" }
if ($secret -eq 'changeme-velocity-modern-forwarding-secret') {
    Write-Warning "VELOCITY_FORWARDING_SECRET usa el valor por defecto. Cambialo en .env para produccion."
}

# Parametros por modo
if ($Mode -eq 'full') {
    $paperServers = @("lobby", "main", "creative")
    $servers = "lobby = `"lobby:25565`"`nmain = `"main:25565`"`ncreative = `"creative:25565`""
    $try = '["lobby"]'
    $allowEnd = 'true'
}
else {
    $paperServers = @("main")
    $servers = 'main = "main:25565"'
    $try = '["main"]'
    $allowEnd = 'false'
}

$gen = Join-Path $root "minecraft\.generated"
New-Item -ItemType Directory -Force -Path (Join-Path $gen "velocity") | Out-Null
# Los Paper montan el DIRECTORIO config completo (Docker crearia /data/config como root
# si montaramos un archivo suelto, y Paper no podria escribir sus otros configs).
foreach ($d in $paperServers) {
    New-Item -ItemType Directory -Force -Path (Join-Path $gen "$d\config") | Out-Null
}
# Directorios de plugins (los rellena el servicio one-shot plugin-build).
foreach ($d in @("main", "lobby", "creative")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $gen "$d\plugins") | Out-Null
}

$enc = [System.Text.UTF8Encoding]::new($false)

# velocity.toml (sin secreto; servidores/try segun modo) + forwarding.secret
$vtpl = (Get-Content (Join-Path $root "minecraft\proxy-velocity\velocity.toml.template") -Raw) -replace "`r`n", "`n"
$vout = $vtpl.Replace('%%SERVERS%%', $servers).Replace('%%TRY%%', $try)
[System.IO.File]::WriteAllText((Join-Path $gen "velocity\velocity.toml"), $vout, $enc)
[System.IO.File]::WriteAllText((Join-Path $gen "velocity\forwarding.secret"), $secret, $enc)

# config/paper-global.yml para cada servidor Paper (con el secreto inline)
$tpl = (Get-Content (Join-Path $root "minecraft\paper-global.yml.template") -Raw) -replace "`r`n", "`n"
foreach ($s in $paperServers) {
    $out = $tpl.Replace('%%FORWARDING_SECRET%%', $secret)
    [System.IO.File]::WriteAllText((Join-Path $gen "$s\config\paper-global.yml"), $out, $enc)
}

# bukkit.yml de 'main' (End segun modo)
$bukkit = (Get-Content (Join-Path $root "minecraft\bukkit.yml.template") -Raw) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText((Join-Path $gen "main\bukkit.yml"), $bukkit.Replace('%%ALLOW_END%%', $allowEnd), $enc)

Write-Host "Config de Minecraft generada (modo: $Mode) en minecraft/.generated/." -ForegroundColor Green
