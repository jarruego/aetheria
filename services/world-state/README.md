# World-State

Read-model del mundo: expone **resumenes estructurados** (recuentos de ciudades,
parcelas, propietarios, NPC...), **nunca bloques crudos**. Es la fuente de contexto para
el planner de la IA (ADR-0006).

## Endpoints

| Metodo | Ruta | Descripcion |
|---|---|---|
| GET | `/health` | Salud del servicio (incluye estado de la DB) |
| GET | `/internal/worlds` | Lista de mundos |
| GET | `/internal/world/{key}/summary` | Resumen estructurado de un mundo |

## Base de datos

Se conecta a `DATABASE_URL` (en Docker: el servicio `postgres`; en prod: Supabase). El
esquema se aplica con las migraciones versionadas (`db/supabase/migrations/` via
`db/migrate.sh`). El servicio degrada con 503 si la DB no esta disponible, sin caer.

## Desarrollo

```bash
pip install -e ".[dev]"
uvicorn aetheria_world.main:app --app-dir src --port 8070 --reload
pytest
```
