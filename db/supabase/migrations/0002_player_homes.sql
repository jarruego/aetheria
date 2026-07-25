-- ============================================================
-- Aetheria - Migracion 0002: casas de jugador (Fase 5)
-- Primer uso del camino de ESCRITURA a la DB. Una casa por jugador y servidor
-- (los mundos de distintos servidores pueden llamarse igual, "world").
-- ============================================================

create table if not exists player_homes (
    player_id  uuid not null references players(id) on delete cascade,
    server     text not null,               -- 'main', 'creative', ...
    world      text not null,
    x          double precision not null,
    y          double precision not null,
    z          double precision not null,
    yaw        real not null default 0,
    pitch      real not null default 0,
    updated_at timestamptz not null default now(),
    primary key (player_id, server)
);

create index if not exists idx_player_homes_player on player_homes(player_id);
