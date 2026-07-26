-- ============================================================
-- Aetheria AI - Migracion 0005: cronica del mundo (Fase 8)
-- El mundo evoluciona solo (simulacion por ticks en el backend). Cada suceso
-- autonomo (produccion economica, mercado...) queda registrado aqui como una
-- "cronica" que los jugadores pueden consultar al volver.
-- ============================================================

create table if not exists world_events (
    id              uuid primary key default gen_random_uuid(),
    kind            text not null,                    -- economy | market | ...
    description     text not null,                    -- frase legible para el jugador
    data            jsonb not null default '{}'::jsonb,
    created_at      timestamptz not null default now()
);

create index if not exists idx_world_events_created on world_events(created_at desc);
