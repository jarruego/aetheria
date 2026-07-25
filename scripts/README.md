# Scripts

Despliegue y tareas reproducibles. **Nada se instala a mano.**

| Script | Plataforma | Qué hace |
|---|---|---|
| `dev-up.ps1` / `.sh` | Win / Unix | Levanta el sistema en modo **lean** (solo `main`, sin lobby ni End) — igual que el cloud |
| `dev-up-full.ps1` / `.sh` | Win / Unix | Levanta el **sistema completo local**: lobby + main + End + más memoria |
| `gen-mc-config.ps1` / `.sh` | Win / Unix | Genera la config de runtime de Minecraft. Acepta modo: `lean` (defecto) o `full` |

## Modo lean vs full

- **lean** (defecto, y lo que corre en Oracle): solo `main`, sin lobby, End desactivado,
  memoria ajustada. `docker compose up`.
- **full** (local, con hardware de sobra): lobby + main + End, más memoria y distancias.
  Usa el override `docker-compose.full.yml`:
  ```bash
  ./scripts/dev-up-full.ps1          # Windows
  ./scripts/dev-up-full.sh           # Linux/macOS
  # equivalente manual:
  ./scripts/gen-mc-config.ps1 -Mode full
  docker compose -f docker-compose.yml -f docker-compose.full.yml up -d
  ```
  Dentro del juego, para saltar de mundo: `/server main` · `/server lobby`.

Próximos (por fase): backups, aprovisionamiento con Terraform.
