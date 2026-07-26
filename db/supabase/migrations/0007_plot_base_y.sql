-- ============================================================
-- Aetheria AI - Migracion 0007: altura de referencia de la parcela
-- La proteccion de una parcela ya no cubre toda la columna: se limita a una banda
-- vertical alrededor de la altura a la que se reclamo (base_y).
-- ============================================================

alter table plots add column if not exists base_y integer;
