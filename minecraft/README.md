# Minecraft (capa de presentación)

Minecraft es solo la **cara visible** de Aetheria. La lógica importante vive en el
backend (`services/`) y la persistencia en Supabase (`db/`).

## Componentes

| Carpeta | Qué es | Fase |
|---|---|---|
| `proxy-velocity/` | Velocity: único punto de entrada de la red | 1 |
| `server-lobby/` | Paper: bienvenida, tutorial, portales (sin economía) | 1 |
| `server-main/` | Paper: mundo persistente + plugin Aetheria | 1 |
| `plugin-aetheria/` | Plugin Java: ejecuta planes validados, mueve NPC | 1/3 |

## Reglas

- Nadie se conecta a Paper directamente: **todo pasa por Velocity** (ADR-0005).
- Compatibilidad Java + Bedrock (móvil, consola) vía **Geyser + Floodgate**.
- Las configuraciones se versionan aquí; los **jars, mundos y logs NO** (ver
  `.gitignore`). Se descargan/generan por script en el despliegue.
- **Autenticación**: `VELOCITY_ONLINE_MODE` en `.env` (por defecto `true` = premium).
  `gen-mc-config` lo escribe a la vez en `velocity.toml` y en el bloque `velocity` de
  `paper-global.yml`: los dos deben coincidir o Paper rechaza los UUID del proxy.
  Con `false` entra cualquier launcher sin cuenta (TLauncher), pero **cualquiera puede
  suplantar cualquier nick** y los UUID son otros → saldo, casas, parcelas y memoria de
  los NPC no se comparten con las cuentas premium. Solo para pruebas en LAN.

En Fase 1 estos directorios se rellenan con configuración declarativa y las imágenes
`itzg/mc-proxy` e `itzg/minecraft-server` en `docker-compose.yml`.
