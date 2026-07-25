-- ============================================================
-- Aetheria - Migracion 0003: memoria de conversacion de NPC (Fase 5)
-- Cada turno (jugador o NPC) se guarda para que el NPC te RECUERDE.
-- ============================================================

create table if not exists npc_conversations (
    id          uuid primary key default gen_random_uuid(),
    npc_key     text not null,          -- 'guia-main', 'guia-vuelta', ...
    player_uuid text not null,          -- UUID del jugador en el juego
    role        text not null,          -- 'player' | 'npc'
    content     text not null,
    created_at  timestamptz not null default now()
);

create index if not exists idx_npc_conv on npc_conversations(npc_key, player_uuid, created_at);
