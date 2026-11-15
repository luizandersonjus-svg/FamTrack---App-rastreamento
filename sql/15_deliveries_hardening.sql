-- ============================================================
-- 15_deliveries_hardening.sql
-- Modo Entregas: autoria e horários pelo servidor, policies via função
-- auxiliar e reordenação da rota numa transação só.
-- ------------------------------------------------------------
-- 1) is_family_member(family_id): auxiliar SECURITY DEFINER (mesmo padrão
--    de is_family_admin, sql/10). As 4 policies de deliveries passam a
--    usá-la em vez de consultar family_members direto.
--
-- 2) Trigger deliveries_server_fields (BEFORE INSERT/UPDATE):
--      INSERT  created_by = auth.uid(), created_at = now()
--      UPDATE  family_id, created_by e created_at não mudam
--      delivered  true  -> delivered_at = now(), delivered_by = auth.uid()
--                 false -> delivered_at/delivered_by = NULL
--      archived   true  -> archived_at = now() (now() é o mesmo para a
--                          transação inteira, então o lote encerrado junto
--                          recebe o mesmo valor e o Histórico agrupa certo)
--                 false -> archived_at = NULL
--    O cliente pode continuar mandando esses campos; o servidor ignora e
--    grava os próprios valores (ninguém registra entrega em nome de outro,
--    e a hora não depende do relógio do aparelho).
--
-- 3) reorder_deliveries(p_family_id, p_ids): grava route_order = posição
--    de cada id no array, num único UPDATE (tudo ou nada). Só mexe em
--    pacotes ativos da família do chamador.
--
-- Regra de negócio mantida (sql/13): qualquer membro aceito da família
-- lê e escreve; quem entrega nem sempre é o admin.
--
-- Idempotente. Não apaga dados. Requisito: 13 e 14 aplicados.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1) Auxiliar de membro + policies
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_family_member(p_family_id uuid)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.family_members
    WHERE family_id = p_family_id
      AND user_id = auth.uid()
      AND accepted = true
  )
$$;

REVOKE ALL ON FUNCTION public.is_family_member(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_family_member(uuid) TO authenticated;

DROP POLICY IF EXISTS "deliveries_select" ON public.deliveries;
CREATE POLICY "deliveries_select" ON public.deliveries
  FOR SELECT TO authenticated
  USING (public.is_family_member(family_id));

DROP POLICY IF EXISTS "deliveries_insert" ON public.deliveries;
CREATE POLICY "deliveries_insert" ON public.deliveries
  FOR INSERT TO authenticated
  WITH CHECK (public.is_family_member(family_id));

DROP POLICY IF EXISTS "deliveries_update" ON public.deliveries;
CREATE POLICY "deliveries_update" ON public.deliveries
  FOR UPDATE TO authenticated
  USING (public.is_family_member(family_id))
  WITH CHECK (public.is_family_member(family_id));

DROP POLICY IF EXISTS "deliveries_delete" ON public.deliveries;
CREATE POLICY "deliveries_delete" ON public.deliveries
  FOR DELETE TO authenticated
  USING (public.is_family_member(family_id));

-- ----------------------------------------------------------------------------
-- 2) Campos preenchidos pelo servidor
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.deliveries_server_fields()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.created_by := auth.uid();
    NEW.created_at := now();
    IF NEW.delivered THEN
      NEW.delivered_at := now();
      NEW.delivered_by := auth.uid();
    ELSE
      NEW.delivered_at := NULL;
      NEW.delivered_by := NULL;
    END IF;
    NEW.archived_at := CASE WHEN NEW.archived THEN now() ELSE NULL END;
    RETURN NEW;
  END IF;

  -- UPDATE: identidade e autoria do pacote são imutáveis.
  NEW.family_id := OLD.family_id;
  NEW.created_by := OLD.created_by;
  NEW.created_at := OLD.created_at;

  IF NEW.delivered IS DISTINCT FROM OLD.delivered THEN
    IF NEW.delivered THEN
      NEW.delivered_at := now();
      NEW.delivered_by := auth.uid();
    ELSE
      NEW.delivered_at := NULL;
      NEW.delivered_by := NULL;
    END IF;
  ELSE
    NEW.delivered_at := OLD.delivered_at;
    NEW.delivered_by := OLD.delivered_by;
  END IF;

  IF NEW.archived IS DISTINCT FROM OLD.archived THEN
    NEW.archived_at := CASE WHEN NEW.archived THEN now() ELSE NULL END;
  ELSE
    NEW.archived_at := OLD.archived_at;
  END IF;

  RETURN NEW;
END;
$$;

-- Fora da API pública (mesma regra do sql/12 para funções de trigger).
REVOKE EXECUTE ON FUNCTION public.deliveries_server_fields() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS deliveries_server_fields ON public.deliveries;
CREATE TRIGGER deliveries_server_fields
  BEFORE INSERT OR UPDATE ON public.deliveries
  FOR EACH ROW EXECUTE FUNCTION public.deliveries_server_fields();

-- ----------------------------------------------------------------------------
-- 3) Reordenação atômica
-- ----------------------------------------------------------------------------
-- SECURITY INVOKER: o UPDATE passa pelas policies de deliveries; a
-- checagem explícita só dá uma mensagem de erro clara.
CREATE OR REPLACE FUNCTION public.reorder_deliveries(p_family_id uuid, p_ids uuid[])
RETURNS integer
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_count integer;
BEGIN
  IF NOT public.is_family_member(p_family_id) THEN
    RAISE EXCEPTION 'not a member of this family' USING ERRCODE = '42501';
  END IF;
  IF p_ids IS NULL OR cardinality(p_ids) = 0 THEN
    RETURN 0;
  END IF;
  IF cardinality(p_ids) > 500 THEN
    RAISE EXCEPTION 'too many deliveries' USING ERRCODE = '22023';
  END IF;

  UPDATE public.deliveries d
     SET route_order = o.pos::integer
    FROM unnest(p_ids) WITH ORDINALITY AS o(id, pos)
   WHERE d.id = o.id
     AND d.family_id = p_family_id
     AND d.archived = false;

  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

REVOKE ALL ON FUNCTION public.reorder_deliveries(uuid, uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reorder_deliveries(uuid, uuid[]) TO authenticated;

COMMIT;

NOTIFY pgrst, 'reload schema';

-- ----------------------------------------------------------------------------
-- VERIFICAÇÃO (esperado: 4 políticas, trigger = 1, 2 funções)
-- ----------------------------------------------------------------------------
SELECT
  (SELECT count(*) FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'deliveries') AS politicas,
  (SELECT count(*) FROM pg_trigger
    WHERE tgname = 'deliveries_server_fields' AND NOT tgisinternal) AS trigger,
  (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN ('is_family_member', 'reorder_deliveries')) AS funcoes;
