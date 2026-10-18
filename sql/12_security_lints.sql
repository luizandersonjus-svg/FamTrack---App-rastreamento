-- ============================================================
-- 12_security_lints.sql
-- Ajustes apontados pelo Security Advisor do Supabase (após o 11).
-- ------------------------------------------------------------
-- 1) Funções de TRIGGER expostas na API (/rest/v1/rpc/...):
--      sos_rate_limit, sos_resolve_audit (SECURITY DEFINER) e
--      handle_updated_at. Por padrão o Supabase dá EXECUTE a anon e
--      authenticated em toda função nova do schema public.
--    Funções de trigger não podem ser chamadas diretamente (o Postgres
--    recusa), mas ficam listadas na API. Revogamos o EXECUTE de
--    PUBLIC/anon/authenticated. Os triggers continuam disparando: o
--    Postgres não confere EXECUTE quando o próprio trigger chama a função
--    (a permissão só é exigida no CREATE TRIGGER).
--
-- 2) handle_updated_at sem search_path fixo (lint 0011): fixado.
--
-- Não altera dados nem a lógica das funções. Idempotente.
-- Requisito: 01..11 aplicados.
-- ============================================================

BEGIN;

-- 1) Trigger functions fora da API pública.
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.oid::regprocedure AS fn
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN ('sos_rate_limit', 'sos_resolve_audit', 'handle_updated_at')
      AND p.prorettype = 'trigger'::regtype
  LOOP
    EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC, anon, authenticated', r.fn);
  END LOOP;
END $$;

-- 2) search_path fixo (só usa now(), que está em pg_catalog).
DO $$
BEGIN
  IF to_regprocedure('public.handle_updated_at()') IS NOT NULL THEN
    ALTER FUNCTION public.handle_updated_at() SET search_path = public, pg_temp;
  END IF;
END $$;

COMMIT;

NOTIFY pgrst, 'reload schema';

-- ----------------------------------------------------------------------------
-- VERIFICAÇÃO (esperado: ok = true em todas as linhas)
-- ----------------------------------------------------------------------------
SELECT p.proname AS funcao,
       NOT has_function_privilege('anon', p.oid, 'EXECUTE')
         AND NOT has_function_privilege('authenticated', p.oid, 'EXECUTE') AS ok_sem_acesso_api,
       (p.proname <> 'handle_updated_at' OR p.proconfig IS NOT NULL) AS ok_search_path
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public'
  AND p.proname IN ('sos_rate_limit', 'sos_resolve_audit', 'handle_updated_at')
ORDER BY 1;
