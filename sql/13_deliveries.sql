-- ============================================================
-- 13_deliveries.sql
-- Modo Entregas: pacotes a entregar, com bandeirinha no mapa.
-- ------------------------------------------------------------
-- Cada linha é um pacote da família: número do pacote (o que aparece
-- na bandeira), rua, número da casa/comércio, tipo de local, posição
-- no mapa, ordem na rota e se já foi entregue.
--
-- Diferente de places/geofences (escrita só por admin), aqui QUALQUER
-- membro aceito da família pode cadastrar, marcar como entregue,
-- reordenar e excluir: quem entrega nem sempre é o admin.
--
-- Idempotente: pode ser re-executado. Não altera tabelas existentes.
-- Requisito: 01..12 aplicados.
-- ============================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.deliveries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  family_id uuid NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
  package_number text NOT NULL CHECK (char_length(package_number) BETWEEN 1 AND 20),
  street text NOT NULL CHECK (char_length(street) BETWEEN 1 AND 200),
  house_number text NOT NULL DEFAULT '' CHECK (char_length(house_number) <= 20),
  place_type text NOT NULL DEFAULT 'RESIDENCIA'
    CHECK (place_type IN ('RESIDENCIA', 'COMERCIO')),
  note text CHECK (note IS NULL OR char_length(note) <= 300),
  latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  route_order integer NOT NULL DEFAULT 0,
  delivered boolean NOT NULL DEFAULT false,
  delivered_at timestamptz,
  delivered_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_by uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_deliveries_family_order
  ON public.deliveries(family_id, route_order);

ALTER TABLE public.deliveries ENABLE ROW LEVEL SECURITY;

-- Membros aceitos da família: leitura e escrita completas.
DROP POLICY IF EXISTS "deliveries_select" ON public.deliveries;
CREATE POLICY "deliveries_select" ON public.deliveries
  FOR SELECT TO authenticated
  USING (
    family_id IN (
      SELECT family_id FROM public.family_members
      WHERE user_id = auth.uid() AND accepted = true
    )
  );

DROP POLICY IF EXISTS "deliveries_insert" ON public.deliveries;
CREATE POLICY "deliveries_insert" ON public.deliveries
  FOR INSERT TO authenticated
  WITH CHECK (
    family_id IN (
      SELECT family_id FROM public.family_members
      WHERE user_id = auth.uid() AND accepted = true
    )
  );

DROP POLICY IF EXISTS "deliveries_update" ON public.deliveries;
CREATE POLICY "deliveries_update" ON public.deliveries
  FOR UPDATE TO authenticated
  USING (
    family_id IN (
      SELECT family_id FROM public.family_members
      WHERE user_id = auth.uid() AND accepted = true
    )
  )
  WITH CHECK (
    family_id IN (
      SELECT family_id FROM public.family_members
      WHERE user_id = auth.uid() AND accepted = true
    )
  );

DROP POLICY IF EXISTS "deliveries_delete" ON public.deliveries;
CREATE POLICY "deliveries_delete" ON public.deliveries
  FOR DELETE TO authenticated
  USING (
    family_id IN (
      SELECT family_id FROM public.family_members
      WHERE user_id = auth.uid() AND accepted = true
    )
  );

-- Sem acesso para visitantes não autenticados.
REVOKE ALL ON public.deliveries FROM anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.deliveries TO authenticated;

COMMIT;

NOTIFY pgrst, 'reload schema';

-- ----------------------------------------------------------------------------
-- VERIFICAÇÃO (esperado: rls_ligado = true e 4 políticas)
-- ----------------------------------------------------------------------------
SELECT c.relrowsecurity AS rls_ligado,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'public' AND tablename = 'deliveries') AS politicas
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname = 'deliveries';
