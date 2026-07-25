# Genera la configuracion de runtime de la red Minecraft desde las plantillas.
# Inyecta el secreto de forwarding (desde .env) en archivos que quedan FUERA de git.
# Uso:  ./scripts/gen-mc-config.ps1
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

$gen = Join-Path $root "minecraft\.generated"
New-Item -ItemType Directory -Force -Path (Join-Path $gen "velocity") | Out-Null
# Los Paper montan el DIRECTORIO config completo (Docker crearia /data/config como root
# si montaramos un archivo suelto, y Paper no podria escribir sus otros configs).
foreach ($d in @("lobby", "main")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $gen "$d\config") | Out-Null
}
# Directorio de plugins de 'main' (lo rellena el servicio one-shot plugin-build).
New-Item -ItemType Directory -Force -Path (Join-Path $gen "main\plugins") | Out-Null

$enc = [System.Text.UTF8Encoding]::new($false)

# velocity.toml (sin secreto) + forwarding.secret (el secreto, en su propio fichero)
Copy-Item (Join-Path $root "minecraft\proxy-velocity\velocity.toml") (Join-Path $gen "velocity\velocity.toml") -Force
[System.IO.File]::WriteAllText((Join-Path $gen "velocity\forwarding.secret"), $secret, $enc)

# config/paper-global.yml para lobby y main (con el secreto inline)
$tpl = (Get-Content (Join-Path $root "minecraft\paper-global.yml.template") -Raw) -replace "`r`n", "`n"
foreach ($s in @("lobby", "main")) {
    $out = $tpl.Replace('%%FORWARDING_SECRET%%', $secret)
    [System.IO.File]::WriteAllText((Join-Path $gen "$s\config\paper-global.yml"), $out, $enc)
}

Write-Host "Config de Minecraft generada en minecraft/.generated/ (velocity, lobby, main)." -ForegroundColor Green
