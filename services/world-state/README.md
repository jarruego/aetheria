# World-State

Mantiene el **modelo del mundo** que consume la IA: resúmenes estructurados, **nunca
bloques crudos**. Enviar millones de bloques a un LLM es inviable y caro; en su lugar,
este servicio expone vistas como:

- Parcelas y propietarios
- Ciudades y carreteras
- Biomas
- Construcciones (metadatos, no bloques)
- Economía e inventarios (resúmenes)

## Estado

**Placeholder de Fase 0.** La implementación llega en **Fase 2**, cuando exista el
esquema de base de datos poblado (ver `db/supabase/migrations/`) y el flujo de eventos
desde el plugin.

Diseño previsto: FastAPI + acceso de solo lectura optimizado a Supabase, con
"snapshots" identificables (`context_ref`) que el planner referencia al generar planes.
