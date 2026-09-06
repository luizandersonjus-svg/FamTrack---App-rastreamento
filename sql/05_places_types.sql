-- ============================================================
-- 05_places_types.sql
-- ------------------------------------------------------------
-- Alinha a CHECK constraint de public.places.type com os tipos
-- suportados pelo app FamTrack (tela de Locais - F3).
--
-- FONTE DA VERDADE (Kotlin): lista de chips em
--   android/.../feature/places/PlaceEditScreen.kt
--   listOf("CASA", "ESCOLA", "TRABALHO", "IGRJA", "MERCADO",
--          "RESTAURANTE", "FARMACIA", "ACADEMIA", "SHOPPING", "OUTRO")
--
-- A constraint antiga só aceitava ('CASA','ESCOLA','TRABALHO','OUTRO').
-- Como os novos valores (ex.: IGRJA, MERCADO) existiam apenas no
-- Kotlin, o PostgREST rejeitava o insert com
--   "new row for relation places violates check constraint
--    places_type_check" (SQLSTATE 23514).
--
-- Idempotente: pode ser re-executado sem afetar dados nem RLS.
-- NÃO usa DROP TABLE / TRUNCATE / DELETE e NÃO toca em políticas.
-- ============================================================

begin;

alter table public.places
  drop constraint if exists places_type_check;

alter table public.places
  add constraint places_type_check
  check (type in (
    'CASA',      -- Home
    'ESCOLA',    -- School
    'TRABALHO',  -- Work
    'IGRJA',     -- Church (igreja)
    'MERCADO',   -- LocalGroceryStore (mercado/supermercado)
    'RESTAURANTE', -- Restaurant
    'FARMACIA',  -- LocalPharmacy
    'ACADEMIA',  -- FitnessCenter
    'SHOPPING',  -- LocalMall
    'OUTRO'      -- Place (padrão)
  ));

commit;