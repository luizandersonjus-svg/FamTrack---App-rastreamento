-- ============================================================
-- 07_locations_upsert.sql — ETAPA 1: Upsert atômico de
-- "localização atual" em public.locations.
-- ------------------------------------------------------------
-- O que muda:
--   1) PRÉ-LIMPEZA (idempotente): garante que não existam duplicatas
--      por (family_id, user_id). Apaga APENAS registros mais antigos para
--      os quais existe OUTRA linha mais nova com o mesmo par. Quando a
--      constraint do passo (2) já existe, este passo não encontra nada.
--   2) CHAVE ÚNICA: garante a existência de UNIQUE (family_id, user_id).
--      Se já existir (02_migracao_correcoes.sql), o passo não faz nada.
--   3) FUNÇÃO RPC rpc_upsert_location: INSERT ... ON CONFLICT DO UPDATE
--      atômico — substitui o padrão SELECT depois UPDATE/INSERT do app
--      (que tinha corrida). Valida que o chamador (auth.uid()) é membro
--      aceito da família informada (public.check_user_family) e que só
--      altera a PRÓPRIA linha (user_id = auth.uid()).
--   4) ÍNDICE auxiliar para consultas de frescor (ETAPA 3).
--
-- Destrutivo? Somente o passo (1), e apenas em duplicatas. NÃO remove ou
-- desabilita RLS, NÃO altera policies, NÃO muda colunas existentes, NÃO usa
-- DROP TABLE/TRUNCATE.
--
-- REPLICA IDENTITY: mantém DEFAULT. Suficiente para os eventos INSERT/UPDATE
-- de postgres_changes consumidos pelo app (REPLICA IDENTITY FULL só seria
-- necessário para DELETE com payload completo).
--
-- Idempotente: pode ser executado N vezes. Envolvido em transação.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1) Pré-limpeza: remove duplicatas preservando apenas o registro mais recente
--    (maior created_at; empate decide pelo maior id). Mesma lógica já usada
--    em 02_migracao_correcoes.sql (linhas 28-36).
-- ----------------------------------------------------------------------------
DELETE FROM public.locations a
USING public.locations b
WHERE a.id <> b.id
  AND a.family_id = b.family_id
  AND a.user_id = b.user_id
  AND (
    a.created_at < b.created_at
    OR (a.created_at = b.created_at AND a.id < b.id)
  );

-- ----------------------------------------------------------------------------
-- 2) Garante a constraint UNIQUE (family_id, user_id).
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'locations_family_user_key'
      AND conrelid = 'public.locations'::regclass
  ) THEN
    ALTER TABLE public.locations
      ADD CONSTRAINT locations_family_user_key UNIQUE (family_id, user_id);
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 3) Função RPC de upsert atômico (SECURITY DEFINER).
--    Regras que REPLICAM as policies atuais do app (não as afrouxam):
--      * o usuário precisa ser membro aceito da família do registro;
--      * o usuário só pode inserir/atualizar a própria linha.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.rpc_upsert_location(
  p_family_id uuid,
  p_user_id uuid,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy double precision DEFAULT NULL,
  p_speed double precision DEFAULT NULL,
  p_bearing double precision DEFAULT NULL,
  p_battery_level integer DEFAULT NULL,
  p_last_updated_at bigint DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_family_id NOT IN (SELECT * FROM public.check_user_family(auth.uid())) THEN
    RAISE EXCEPTION 'Usuario nao e membro aceito da familia informada';
  END IF;

  IF p_user_id <> auth.uid() THEN
    RAISE EXCEPTION 'So e permitido atualizar a propria localizacao';
  END IF;

  INSERT INTO public.locations (
    family_id, user_id, latitude, longitude, accuracy, speed, bearing,
    battery_level, last_updated_at
  )
  VALUES (
    p_family_id, p_user_id, p_latitude, p_longitude,
    p_accuracy, p_speed, p_bearing, p_battery_level, p_last_updated_at
  )
  ON CONFLICT (family_id, user_id)
  DO UPDATE SET
    latitude        = EXCLUDED.latitude,
    longitude       = EXCLUDED.longitude,
    accuracy        = EXCLUDED.accuracy,
    speed           = EXCLUDED.speed,
    bearing         = EXCLUDED.bearing,
    battery_level   = EXCLUDED.battery_level,
    last_updated_at = EXCLUDED.last_updated_at,
    created_at      = now();
END;
$$;

REVOKE ALL ON FUNCTION public.rpc_upsert_location(
  uuid, uuid, double precision, double precision, double precision,
  double precision, double precision, integer, bigint
) FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.rpc_upsert_location(
  uuid, uuid, double precision, double precision, double precision,
  double precision, double precision, integer, bigint
) TO authenticated;

-- ----------------------------------------------------------------------------
-- 4) Índice auxiliar para consultas de frescor (ETAPA 3). Idempotente.
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_locations_last_updated_at
  ON public.locations (last_updated_at);

COMMIT;