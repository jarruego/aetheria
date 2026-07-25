-- ============================================================
-- Aetheria AI - Migración inicial (0001)
-- Toda la persistencia vive aquí (no en YAML). Esquema versionado.
-- Postgres / Supabase. Idempotente donde es razonable.
-- ============================================================

create extension if not exists "pgcrypto";  -- gen_random_uuid()

-- ---------- Jugadores ----------
-- Un jugador puede entrar por Java o Bedrock (Floodgate). Guardamos ambos ids.
create table if not exists players (
    id              uuid primary key default gen_random_uuid(),
    java_uuid       uuid unique,
    bedrock_xuid    text unique,
    username        text not null,
    first_seen      timestamptz not null default now(),
    last_seen       timestamptz not null default now(),
    created_at      timestamptz not null default now()
);

-- ---------- Mundos ----------
-- Preparado para múltiples mundos (main, creativo, eventos, otros continentes...).
create table if not exists worlds (
    id              uuid primary key default gen_random_uuid(),
    key             text unique not null,          -- 'main', 'lobby', 'creative'...
    display_name    text not null,
    persistent      boolean not null default true,
    created_at      timestamptz not null default now()
);

-- ---------- Ciudades ----------
create table if not exists cities (
    id              uuid primary key default gen_random_uuid(),
    world_id        uuid not null references worlds(id) on delete cascade,
    name            text not null,
    center_x        integer not null,
    center_z        integer not null,
    created_at      timestamptz not null default now()
);

-- ---------- Parcelas ----------
create table if not exists plots (
    id              uuid primary key default gen_random_uuid(),
    world_id        uuid not null references worlds(id) on delete cascade,
    city_id         uuid references cities(id) on delete set null,
    owner_id        uuid references players(id) on delete set null,
    min_x           integer not null,
    min_z           integer not null,
    max_x           integer not null,
    max_z           integer not null,
    created_at      timestamptz not null default now()
);

-- ---------- Economía ----------
-- Cuentas de saldo por jugador (extensible a empresas/gobiernos con owner_type).
create table if not exists accounts (
    id              uuid primary key default gen_random_uuid(),
    owner_type      text not null default 'player',   -- player | company | government
    owner_id        uuid not null,
    balance         numeric(20,2) not null default 0,
    currency        text not null default 'AET',
    created_at      timestamptz not null default now(),
    unique (owner_type, owner_id, currency)
);

create table if not exists transactions (
    id              uuid primary key default gen_random_uuid(),
    from_account    uuid references accounts(id),
    to_account      uuid references accounts(id),
    amount          numeric(20,2) not null check (amount > 0),
    reason          text,
    created_at      timestamptz not null default now()
);

-- ---------- NPC ----------
-- Los NPC son entidades del mundo; la IA solo conversa/decide. Aquí su estado lógico.
create table if not exists npcs (
    id              uuid primary key default gen_random_uuid(),
    world_id        uuid not null references worlds(id) on delete cascade,
    key             text unique not null,           -- 'arquitecto-01'
    role            text not null,                  -- arquitecto | urbanista | ...
    display_name    text not null,
    home_x          integer,
    home_y          integer,
    home_z          integer,
    created_at      timestamptz not null default now()
);

-- Memoria del NPC (hechos/recuerdos estructurados; no historial de chat crudo).
create table if not exists npc_memory (
    id              uuid primary key default gen_random_uuid(),
    npc_id          uuid not null references npcs(id) on delete cascade,
    player_id       uuid references players(id) on delete set null,
    kind            text not null,                  -- fact | preference | event
    content         jsonb not null,
    created_at      timestamptz not null default now()
);

-- ---------- Encargos / Contratos ----------
create table if not exists contracts (
    id              uuid primary key default gen_random_uuid(),
    player_id       uuid references players(id) on delete set null,
    npc_id          uuid references npcs(id) on delete set null,
    service         text not null,                  -- 'arquitecto_ia', 'urbanista_ia'...
    status          text not null default 'pending', -- pending | in_progress | done | cancelled
    spec            jsonb not null default '{}'::jsonb,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- ---------- Historial de planes (auditoría de la IA) ----------
-- Cada plan generado queda registrado, aprobado o rechazado, para auditoría.
create table if not exists plan_audit (
    id              uuid primary key default gen_random_uuid(),
    plan_id         uuid not null,
    actor_type      text not null,
    actor_id        text not null,
    status          text not null,                  -- approved | rejected
    rejection_reason text,
    actions         jsonb not null default '[]'::jsonb,
    created_at      timestamptz not null default now()
);

-- ---------- Índices útiles ----------
create index if not exists idx_plots_owner on plots(owner_id);
create index if not exists idx_plots_world on plots(world_id);
create index if not exists idx_npc_memory_npc on npc_memory(npc_id);
create index if not exists idx_contracts_player on contracts(player_id);
create index if not exists idx_transactions_created on transactions(created_at);

-- ---------- Semilla mínima ----------
insert into worlds (key, display_name, persistent)
values ('main', 'Mundo Principal', true),
       ('lobby', 'Lobby', false)
on conflict (key) do nothing;
