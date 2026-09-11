-- ============================================================
-- 08_telemetry.sql — ETAPA 7: Observabilidade da cadeia de
-- localização (healthcheck) via tabela de telemetria.
-- ------------------------------------------------------------
-- O que muda:
--   1) TABELA public.telemetry: uma linha por check-in do
--      HealthCheckWorker (a cada 10 min por aparelho). Carrega
--      o estado da cadeia LBS: idade do último fix do serviço,
--      idade do último upsert (locations), contadores de sucesso/
--      falha de UPSERT/SYNC, total de transições de geofence,
--      tamanho da fila offline e nº de estados de geofence no
--      aparelho.
--   2) IDENTIDADE (proteção por chave): cada linha pertence ao
--      usuário logado (user_id = auth.uid()) e carrega um
--      instance_id (UUID gerado por instalação) para distinguir
--      aparelhos. RLS permite APENAS inserir e ler as PRÓPRIAS
--      linhas — ninguém enxerga a telemetria de outro membro.
--   3) ÍNDICE de consulta por (user_id, created_at desc).
--
-- Destrutivo? Não. Não remove/desabilita RLS, não altera
-- policies existentes, não usa DROP TABLE/TRUNCATE.
--
-- Idempotente: pode ser executado N vezes (CREATE IF NOT EXISTS,
-- policies IF NOT EXISTS). Envolvido em transação.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1) Tabela de telemetria (por check-in do HealthCheckWorker).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.telemetry (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  instance_id text NOT NULL,
  app_version text,
  sent_by text NOT NULL DEFAULT 'health_check',
  service_alive_seconds numeric,
  last_fix_seconds numeric,
  last_upsert_seconds numeric,
  fix_count bigint NOT NULL DEFAULT 0,
  upsert_ok bigint NOT NULL DEFAULT 0,
  upsert_fail bigint NOT NULL DEFAULT 0,
  sync_ok bigint NOT NULL DEFAULT 0,
  sync_fail bigint NOT NULL DEFAULT 0,
  geofence_transitions bigint NOT NULL DEFAULT 0,
  queue_size bigint NOT NULL DEFAULT 0,
  geofence_states bigint NOT NULL DEFAULT 0,
  watermark text,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 2) RLS: apenas o próprio usuário pode inserir e ler as próprias linhas.
-- ----------------------------------------------------------------------------
ALTER TABLE public.telemetry ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'telemetry'
      AND policyname = 'telemetry_insert_own'
  ) THEN
    CREATE POLICY telemetry_insert_own ON public.telemetry
      FOR INSERT TO authenticated
      WITH CHECK ((select auth.uid()) = user_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'telemetry'
      AND policyname = 'telemetry_select_own'
  ) THEN
    CREATE POLICY telemetry_select_own ON public.telemetry
      FOR SELECT TO authenticated
      USING ((select auth.uid()) = user_id);
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 3) Índice de leitura (últimas leituras do próprio aparelho).
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS telemetry_user_created_idx
  ON public.telemetry (user_id, created_at DESC);

COMMIT;