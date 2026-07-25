-- Datos de DEMO para desarrollo. NO es una migracion (no se aplica en prod).
-- Aplicar manualmente:  psql "$DATABASE_URL" -f db/seed-dev.sql
-- Idempotente mediante guardas NOT EXISTS.

-- Ciudad de ejemplo en el mundo principal
insert into cities (world_id, name, center_x, center_z)
select w.id, 'Nueva Aetheria', 0, 0
from worlds w
where w.key = 'main'
  and not exists (select 1 from cities c where c.name = 'Nueva Aetheria');

-- Jugador de ejemplo
insert into players (username)
select 'jarruego'
where not exists (select 1 from players where username = 'jarruego');

-- NPC arquitecto de ejemplo
insert into npcs (world_id, key, role, display_name, home_x, home_y, home_z)
select w.id, 'arquitecto-01', 'arquitecto', 'Vitruvio', 10, 64, 10
from worlds w
where w.key = 'main'
on conflict (key) do nothing;

-- Parcela de ejemplo (propiedad del jugador, dentro de la ciudad)
insert into plots (world_id, city_id, owner_id, min_x, min_z, max_x, max_z)
select w.id, c.id, p.id, 0, 0, 16, 16
from worlds w
join cities c on c.name = 'Nueva Aetheria'
join players p on p.username = 'jarruego'
where w.key = 'main'
  and not exists (
    select 1 from plots pl where pl.world_id = w.id and pl.min_x = 0 and pl.min_z = 0
  );
