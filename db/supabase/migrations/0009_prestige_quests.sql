-- ============================================================
-- Aetheria AI - Migracion 0009: misiones y PRESTIGIO del jugador
-- El jugador deja de ser un turista: cumple encargos del pueblo (misiones
-- generadas por CODIGO a partir del estado real de la aldea) y aporta al comun.
-- De ahi sale su PRESTIGIO en esa aldea, que compite en el mismo ranking que el
-- peculio de los aldeanos: el primero de ese ranking es el ALCALDE.
--
-- Las aldeas se identifican por NOMBRE (no hay tabla `villages`), igual que en
-- `world_events.data`: el plugin es la autoridad de la geografia del mundo.
-- ============================================================

-- ---------- Reputacion (prestigio) de un jugador EN UNA ALDEA ----------
-- prestigio = mission_points + floor(sqrt(donated_total))
-- La raiz cuadrada es deliberada (rendimiento decreciente): evita comprar la alcaldia.
create table if not exists player_reputation (
    player_id       uuid not null references players(id) on delete cascade,
    village_name    text not null,
    mission_points  integer not null default 0,      -- ganado cumpliendo misiones (decae si abandonas)
    donated_total   numeric(20,2) not null default 0, -- acumulado historico al arca (NO decae)
    last_activity   timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    primary key (player_id, village_name)
);

-- ---------- Misiones ----------
-- El objetivo y la recompensa los fija el codigo (plugin + baremo del backend);
-- la IA, como mucho, redacta el sabor. Nunca decide que se pide ni cuanto se paga.
create table if not exists quests (
    id              uuid primary key default gen_random_uuid(),
    player_id       uuid not null references players(id) on delete cascade,
    village_name    text not null,
    kind            text not null,             -- economica | social | construccion | mantenimiento | exploracion
    objective       jsonb not null default '{}'::jsonb,   -- {"good": "WHEAT", "amount": 20, ...}
    progress        integer not null default 0,
    target          integer not null,
    reward_aet      integer not null default 0,
    reward_prestige integer not null default 0,
    status          text not null default 'active',   -- active | completed | expired
    created_at      timestamptz not null default now(),
    completed_at    timestamptz
);

create index if not exists idx_quests_player_status on quests(player_id, status);
create index if not exists idx_quests_village on quests(village_name, status);
create index if not exists idx_reputation_village on player_reputation(village_name, mission_points desc);
