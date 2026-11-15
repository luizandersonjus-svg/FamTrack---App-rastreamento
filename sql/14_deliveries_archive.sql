-- ============================================================
-- 14_deliveries_archive.sql
-- Modo Entregas: encerrar a rota sem perder o histórico.
-- ------------------------------------------------------------
-- "Encerrar a rota" marca todos os pacotes ativos da família como
-- arquivados (entregues ou não). Eles somem do mapa e da lista, mas
-- continuam na tabela e aparecem na tela de Histórico, agrupados pelo
-- momento do encerramento (archived_at).
--
--   archived     false = pacote na rota atual; true = no histórico
--   archived_at  quando a rota foi encerrada (igual para o lote todo)
--
-- Idempotente. Não apaga dados. Requisito: 13 aplicado.
-- ============================================================

BEGIN;

ALTER TABLE public.deliveries
  ADD COLUMN IF NOT EXISTS archived boolean NOT NULL DEFAULT false;

ALTER TABLE public.deliveries
  ADD COLUMN IF NOT EXISTS archived_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_deliveries_family_archived
  ON public.deliveries(family_id, archived, archived_at DESC);

COMMIT;

NOTIFY pgrst, 'reload schema';

-- ----------------------------------------------------------------------------
-- VERIFICAÇÃO (esperado: 2 linhas, archived e archived_at)
-- ----------------------------------------------------------------------------
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'deliveries'
  AND column_name IN ('archived', 'archived_at')
ORDER BY column_name;
