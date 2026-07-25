-- ============================================================
-- Aetheria - Migracion 0004: memoria a largo plazo de NPC (Fase 5+)
-- Resumen "divagante" de lo que el NPC recuerda de cada jugador. Los turnos recientes
-- viven en npc_conversations (corto plazo, verbatim); lo viejo se condensa aqui y se
-- borra de alli (se difumina/olvida). Asi la IA nunca se satura y la DB no crece sin fin.
-- ============================================================

create table if not exists npc_player_memory (
    npc_key     text not null,
    player_uuid text not null,
    summary     text not null default '',
    updated_at  timestamptz not null default now(),
    primary key (npc_key, player_uuid)
);
