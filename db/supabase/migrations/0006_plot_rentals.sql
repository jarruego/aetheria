-- ============================================================
-- Aetheria AI - Migracion 0006: alquiler de parcelas
-- Una parcela puede ser en PROPIEDAD (pago unico, para siempre) o en ALQUILER
-- (deposito pequeno + renta que se cobra cada periodo; si no se paga, se libera).
-- ============================================================

alter table plots add column if not exists rental   boolean not null default false;
alter table plots add column if not exists rent      numeric(20,2) not null default 0;
alter table plots add column if not exists rent_due  timestamptz;

create index if not exists idx_plots_rent_due on plots(rent_due) where rental;
