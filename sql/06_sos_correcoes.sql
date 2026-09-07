-- CORRECOES SOS (P0/P1 de auditoria)
-- Execute este SQL no Supabase Dashboard > SQL Editor.
-- Requisito: fix_rls_recursion.sql ja aplicado (funcao check_user_family).
--
-- F1. INSERT valida que o remetente e membro aceito da familia do alerta
--     (antes: so user_id = auth.uid(), permitia alerta em familia alheia).
-- F2. Policy UPDATE para resolver/cancelar. Antes nao existia nenhuma
--     politica de UPDATE -> o RLS negava resolveSos/cancelar.
-- F3. Colunas de auditoria (resolved_at/resolved_by) e de frescor/precisao
--     do fix (accuracy/fix_at) enviadas pelo app.
-- F4. Dedup: no maximo 1 SOS ativo por membro.
-- F5. Rate limit: maximo 5 SOS por familia a cada 1 minuto.

-- ===== F3: novas colunas =====
ALTER TABLE public.sos_alerts
  ADD COLUMN IF NOT EXISTS accuracy double precision,
  ADD COLUMN IF NOT EXISTS fix_at timestamptz,
  ADD COLUMN IF NOT EXISTS resolved_at timestamptz,
  ADD COLUMN IF NOT EXISTS resolved_by uuid;

-- ===== F1: INSERT validado por familia =====
DROP POLICY IF EXISTS "sos_alerts_insert" ON public.sos_alerts;
CREATE POLICY "sos_alerts_insert" ON public.sos_alerts
  FOR insert to authenticated
  WITH CHECK (
    user_id = auth.uid()
    AND family_id IN (SELECT public.check_user_family(auth.uid()))
  );

-- ===== F2: UPDATE restrito aos membros da familia =====
DROP POLICY IF EXISTS "sos_alerts_update" ON public.sos_alerts;
CREATE POLICY "sos_alerts_update" ON public.sos_alerts
  FOR update to authenticated
  USING (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  )
  WITH CHECK (
    family_id IN (SELECT public.check_user_family(auth.uid()))
  );

-- ===== F3: auditoria de resolucao =====
CREATE OR REPLACE FUNCTION public.sos_resolve_audit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF NEW.resolved AND NOT OLD.resolved THEN
    NEW.resolved_at := now();
    NEW.resolved_by := auth.uid();
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS sos_resolve_audit ON public.sos_alerts;
CREATE TRIGGER sos_resolve_audit
  BEFORE UPDATE ON public.sos_alerts
  FOR EACH ROW EXECUTE FUNCTION public.sos_resolve_audit();

-- ===== F4: dedup de SOS ativo por membro =====
CREATE UNIQUE INDEX IF NOT EXISTS sos_alerts_one_active_per_member
  ON public.sos_alerts (family_id, user_id)
  WHERE NOT resolved;

-- ===== F5: rate limit =====
CREATE OR REPLACE FUNCTION public.sos_rate_limit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF (
    SELECT count(*) FROM public.sos_alerts
    WHERE family_id = NEW.family_id
      AND created_at > now() - interval '1 minute'
  ) >= 5 THEN
    RAISE EXCEPTION 'Limite de SOS da familia atingido. Aguarde e tente novamente.';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS sos_rate_limit ON public.sos_alerts;
CREATE TRIGGER sos_rate_limit
  BEFORE INSERT ON public.sos_alerts
  FOR EACH ROW EXECUTE FUNCTION public.sos_rate_limit();