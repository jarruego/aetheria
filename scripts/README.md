# Scripts

Despliegue y tareas reproducibles. **Nada se instala a mano.**

| Script | Plataforma | Qué hace |
|---|---|---|
| `dev-up.ps1` / `.sh` | Win / Unix | Levanta el sistema en modo **lean** (solo `main`, sin lobby ni End) — igual que el cloud |
| `dev-up-full.ps1` / `.sh` | Win / Unix | Levanta el **sistema completo local**: lobby + main + End + más memoria |
| `gen-mc-config.ps1` / `.sh` | Win / Unix | Genera la config de runtime de Minecraft. Acepta modo: `lean` (defecto) o `full` |
| `backup.ps1` | Win | Backup de **DB (pg_dump comprimido) + mundos** a `backups/`; conserva los últimos N (`-Keep`). Sin parar el servidor |

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

## Backups

```powershell
./scripts/backup.ps1            # DB + mundos a backups/ (conserva 14)
./scripts/backup.ps1 -Keep 30   # conserva 30
```

Restaurar la DB:
```bash
gzip -dc backups/db-XXXX.sql.gz | docker compose exec -T postgres psql -U aetheria -d aetheria
```
Un cron/Task Scheduler puede llamarlo a diario. `backups/` está en `.gitignore`.

Próximos (por fase): aprovisionamiento con Terraform, monitorización.
