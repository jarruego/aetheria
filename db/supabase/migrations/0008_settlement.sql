-- ============================================================
-- Aetheria AI - Migracion 0008: poblacion del asentamiento (Fase: pueblo vivo)
-- El pueblo crece cuando prospera y mengua (emigracion) cuando decae. La poblacion
-- OBJETIVO vive aqui; el plugin reconcilia el mundo fisico (casas + habitantes).
-- ============================================================

create table if not exists settlement (
    world       text primary key,
    population  integer not null default 3,
    updated_at  timestamptz not null default now()
);

insert into settlement (world, population) values ('main', 3)
on conflict (world) do nothing;
