-- ============================================================
-- 10_security_hardening.sql — correções de RLS/RPC da auditoria
-- ------------------------------------------------------------
-- Requisitos: 01..09 e fix_rls_recursion.sql já aplicados.
--
-- S1. family_members: a policy "family_members_update_own_flags" deixava o
--     membro alterar QUALQUER coluna da própria linha — virar admin
--     (role), trocar family_id para outra família e passar a ver as
--     localizações dela. Agora o UPDATE direto só alcança as colunas de
--     preferência do próprio membro.
-- S2. insert_family_notifications: não validava o chamador. Qualquer
--     usuário logado podia mandar notificações (inclusive "SOS") para
--     qualquer família, em nome de qualquer remetente.
-- S3. check_user_family(p_user_id): SECURITY DEFINER exposta via RPC,
--     revelava as famílias de qualquer usuário. Passa a responder só
--     para o próprio chamador (as policies já a chamam com auth.uid()).
-- S4. INSERT/UPDATE em locations, route_history e health_snapshots só
--     checavam user_id: dava para gravar pontos no mapa/histórico de uma
--     família alheia. Agora exigem ser membro aceito da família da linha.
-- S5. family_members não tinha policy de DELETE: "sair da família" e
--     "remover membro" não faziam nada (sem erro). Agora o membro pode
--     remover a própria linha e o admin, linhas da própria família.
-- S6. sos_alerts: o UPDATE permitia a qualquer membro reescrever
--     coordenadas/autor do alerta. Só a coluna "resolved" é atualizável
--     (resolved_at/resolved_by continuam preenchidos pelo trigger).
--
-- Não desabilita RLS, não remove dados. Idempotente. Em transação.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- S3) check_user_family: só devolve famílias do próprio chamador.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.check_user_family(p_user_id uuid)
RETURNS SETOF uuid
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
  SELECT family_id FROM public.family_members
  WHERE user_id = p_user_id
    AND p_user_id = auth.uid()
    AND accepted = true
$$;

REVOKE ALL ON FUNCTION public.check_user_family(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.check_user_family(uuid) TO authenticated;

-- Auxiliar (sem recursão de RLS): o chamador é admin aceito da família?
CREATE OR REPLACE FUNCTION public.is_family_admin(p_family_id uuid)
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
      AND role = 'admin'
  )
$$;

REVOKE ALL ON FUNCTION public.is_family_admin(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_family_admin(uuid) TO authenticated;

-- ----------------------------------------------------------------------------
-- S1) family_members: UPDATE direto restrito às colunas de preferência.
--     nickname/avatar_url continuam liberadas para não quebrar as RPCs
--     update_my_nickname/update_my_avatar caso sejam SECURITY INVOKER.
--     role, accepted, family_id e user_id só mudam via RPC SECURITY DEFINER.
-- ----------------------------------------------------------------------------
REVOKE UPDATE ON public.family_members FROM authenticated, anon;
GRANT UPDATE (sharing_paused, share_health, nickname, avatar_url)
  ON public.family_members TO authenticated;

DROP POLICY IF EXISTS "family_members_update_own_flags" ON public.family_members;
CREATE POLICY "family_members_update_own_flags" ON public.family_members
  FOR UPDATE TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- S5) family_members: DELETE (sair da família / admin remove membro).
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "family_members_delete" ON public.family_members;
CREATE POLICY "family_members_delete" ON public.family_members
  FOR DELETE TO authenticated
  USING (
    user_id = auth.uid()
    OR public.is_family_admin(family_id)
  );

-- ----------------------------------------------------------------------------
-- S2) insert_family_notifications: valida remetente e pertença à família.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.insert_family_notifications(
  p_family_id uuid,
  p_sender_user_id uuid,
  p_title text,
  p_message text,
  p_type text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL OR p_sender_user_id IS DISTINCT FROM auth.uid() THEN
    RAISE EXCEPTION 'Remetente invalido';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.family_members
    WHERE family_id = p_family_id
      AND user_id = auth.uid()
      AND accepted = true
  ) THEN
    RAISE EXCEPTION 'Usuario nao e membro aceito da familia informada';
  END IF;

  IF length(coalesce(p_title, '')) > 200 OR length(coalesce(p_message, '')) > 1000 THEN
    RAISE EXCEPTION 'Notificacao muito longa';
  END IF;

  INSERT INTO public.notifications (family_id, user_id, title, message, type, read)
  SELECT fm.family_id, fm.user_id, p_title, p_message, p_type, false
  FROM public.family_members fm
  WHERE fm.family_id = p_family_id
    AND fm.accepted = true
    AND fm.user_id <> auth.uid();
END;
$$;

REVOKE ALL ON FUNCTION public.insert_family_notifications(uuid, uuid, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.insert_family_notifications(uuid, uuid, text, text, text) TO authenticated;

-- ----------------------------------------------------------------------------
-- S4) Escrita só na própria linha E em família da qual é membro aceito.
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "locations_insert" ON public.locations;
CREATE POLICY "locations_insert" ON public.locations
  FOR INSERT TO authenticated
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "locations_update" ON public.locations;
CREATE POLICY "locations_update" ON public.locations
  FOR UPDATE TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "route_history_insert" ON public.route_history;
CREATE POLICY "route_history_insert" ON public.route_history
  FOR INSERT TO authenticated
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "health_snapshots_insert" ON public.health_snapshots;
CREATE POLICY "health_snapshots_insert" ON public.health_snapshots
  FOR INSERT TO authenticated
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "health_snapshots_update" ON public.health_snapshots;
CREATE POLICY "health_snapshots_update" ON public.health_snapshots
  FOR UPDATE TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

-- ----------------------------------------------------------------------------
-- S6) sos_alerts: só "resolved" é atualizável pelo cliente.
-- ----------------------------------------------------------------------------
REVOKE UPDATE ON public.sos_alerts FROM authenticated, anon;
GRANT UPDATE (resolved) ON public.sos_alerts TO authenticated;

COMMIT;

-- Verificação (esperado: as policies acima listadas)
SELECT tablename, policyname, cmd
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN ('family_members', 'locations', 'route_history', 'health_snapshots')
ORDER BY tablename, policyname;
