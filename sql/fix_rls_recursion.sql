-- CORRECAO: infinite recursion nas policies de family_members
-- Execute este SQL no Supabase Dashboard > SQL Editor

-- 1. Criar funcao SECURITY DEFINER para verificar se usuario pertence a familia
CREATE OR REPLACE FUNCTION public.check_user_family(p_user_id uuid)
RETURNS SETOF uuid
LANGUAGE sql
SECURITY DEFINER
STABLE
AS $$
  SELECT family_id FROM public.family_members
  WHERE user_id = p_user_id AND accepted = true
$$;

-- 2. Remover policies antigas de family_members
DROP POLICY IF EXISTS "family_members_select" ON public.family_members;
DROP POLICY IF EXISTS "family_members_insert" ON public.family_members;

-- 3. Criar policies novas usando a funcao (sem recursao)
CREATE POLICY "family_members_select" ON public.family_members
  FOR select to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

CREATE POLICY "family_members_insert" ON public.family_members
  FOR insert to authenticated
  WITH CHECK (
    family_id IN (
      SELECT id FROM public.families
      WHERE creator_id = auth.uid()
    )
  );

-- 4. Corrigir policies de outras tabelas tambem
DROP POLICY IF EXISTS "families_select" ON public.families;
CREATE POLICY "families_select" ON public.families
  FOR select to authenticated
  USING (
    id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "locations_select" ON public.locations;
CREATE POLICY "locations_select" ON public.locations
  FOR select to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "geofences_select" ON public.geofences;
CREATE POLICY "geofences_select" ON public.geofences
  FOR select to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "geofences_all" ON public.geofences;
CREATE POLICY "geofences_all" ON public.geofences
  FOR all to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "sos_alerts_select" ON public.sos_alerts;
CREATE POLICY "sos_alerts_select" ON public.sos_alerts
  FOR select to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

DROP POLICY IF EXISTS "route_history_select" ON public.route_history;
CREATE POLICY "route_history_select" ON public.route_history
  FOR select to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );
