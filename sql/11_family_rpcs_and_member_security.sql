-- ============================================================
-- 11_family_rpcs_and_member_security.sql
-- RPCs de família + blindagem de family_members
-- ------------------------------------------------------------
-- Requisitos: 01..10 e fix_rls_recursion.sql já aplicados (o bloco
-- "PRÉ-REQUISITOS" abaixo aborta tudo se faltar algo).
--
-- O QUE ESTE ARQUIVO FAZ
--
-- A2 (segurança, alta). A policy "family_members_insert"
--     (01_schema / setup_supabase / fix_rls_recursion) deixava o CRIADOR
--     de uma família inserir QUALQUER user_id com accepted = true. O app
--     da vítima (FamilyRepository.getUserFamily pega a 1ª linha aceita)
--     podia passar a enviar a localização para a família do atacante.
--     Agora:
--       * INSERT direto em family_members e em families é PROIBIDO
--         (policy removida + REVOKE INSERT). Linhas só nascem pelas RPCs
--         SECURITY DEFINER abaixo, e sempre para o PRÓPRIO chamador:
--           - create_family: cria a família e adiciona o criador (admin);
--           - join_family_by_code: adiciona o chamador após validar o
--             código de convite (com limite de tentativas).
--       * UPDATE direto em family_members fica restrito a sharing_paused
--         e share_health da própria linha (o 10 também liberava nickname
--         e avatar_url; agora eles só mudam pelas RPCs, que validam).
--         role / accepted / family_id / user_id continuam bloqueados (10).
--       * UPDATE direto em families: revogado (renomear e trocar código
--         só pelas RPCs rename_family / regenerate_invite_code).
--       * UMA família por usuário (modelo do app: todo o cliente usa
--         "a família do usuário"): índice único parcial
--         family_members(user_id) WHERE accepted. Se já houver usuários
--         em mais de uma família, o índice NÃO é criado (nada é apagado)
--         e o resultado final avisa — veja PRÉ-CHECK 2.
--
-- A3. As 6 RPCs que o app chama e que não existiam em sql/:
--       create_family(p_name)                       -> linhas de families
--       join_family_by_code(p_invite_code)          -> linhas de families
--       get_family_members_display(p_family_id)     -> user_id, display_name, avatar_url
--       update_my_nickname(p_nickname)              -> void
--       update_my_avatar(p_avatar_url)              -> void
--       rename_family(p_family_id, p_new_name)      -> void
--     Nomes de parâmetros e formato de retorno seguem exatamente o
--     cliente (FamilyRepository.kt / Models.kt). Atenção: o supabase-kt
--     decodeSingle() lê uma LISTA JSON, por isso create_family e
--     join_family_by_code retornam TABLE (lista com 1 linha), não um
--     objeto único.
--     Também reescreve regenerate_invite_code (mesma assinatura) para usar
--     o gerador forte de códigos (o antigo usava md5 => só A-F/2-9).
--
-- COMPORTAMENTO (útil para o suporte)
--   * create_family: falha se o usuário já participa de uma família.
--   * join_family_by_code:
--       - código inválido/inexistente => devolve lista VAZIA (o app mostra
--         "Código de convite inválido") e a tentativa é registrada;
--       - 10 erros em 15 min ou 30 em 24 h => bloqueia novas tentativas
--         (erro "Muitas tentativas"), liberando sozinho com o tempo;
--       - já é membro DESSA família => devolve a família (idempotente);
--       - já é membro de OUTRA família => erro (precisa sair antes).
--   * get_family_members_display: só para membro aceito da família;
--     display_name = apelido > nome do Google > início do e-mail > "Membro".
--
-- PRÉ-CHECKS (rode ANTES, no SQL Editor, e guarde o resultado)
--
--   PRÉ-CHECK 1 — copia de segurança das RPCs que já existem em produção
--   (este arquivo APAGA e recria essas funções, com qualquer assinatura):
--
--     select p.proname,
--            pg_get_function_identity_arguments(p.oid) as argumentos,
--            pg_get_function_result(p.oid)             as retorno,
--            pg_get_functiondef(p.oid)                 as definicao
--     from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--     where n.nspname = 'public'
--       and p.proname in ('create_family','join_family_by_code',
--                         'get_family_members_display','update_my_nickname',
--                         'update_my_avatar','rename_family',
--                         'regenerate_invite_code');
--
--   PRÉ-CHECK 2 — usuários em MAIS DE UMA família (esperado: 0 linhas).
--   Também serve de auditoria do A2: alguém que nunca entrou numa família
--   e aparece nela pode ter sido incluído à força.
--
--     select fm.user_id, u.email, count(*) as familias,
--            string_agg(f.name || ' [' || fm.family_id || '] role=' || fm.role
--                       || case when f.creator_id = fm.user_id
--                               then ' (criador)' else '' end, ' | ') as detalhes
--     from public.family_members fm
--     join public.families f on f.id = fm.family_id
--     left join auth.users u on u.id = fm.user_id
--     where fm.accepted
--     group by fm.user_id, u.email
--     having count(*) > 1;
--
--   Se vier alguma linha: decida com o usuário qual família fica e apague
--   a(s) outra(s):
--     delete from public.family_members
--     where user_id = '<user_id>' and family_id = '<family_id_que_sai>';
--   Depois rode este arquivo de novo para criar o índice.
--
-- Não desabilita RLS, não apaga dados (exceto tentativas de convite
-- com mais de 24 h). Idempotente. Em transação: se algo falhar, nada muda.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 0) PRÉ-REQUISITOS: aborta se 01..10 não foram aplicados.
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF to_regclass('public.families') IS NULL
     OR to_regclass('public.family_members') IS NULL
     OR NOT EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema = 'public' AND table_name = 'families'
         AND column_name = 'invite_code')
     OR NOT EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema = 'public' AND table_name = 'family_members'
         AND column_name = 'sharing_paused')
     OR to_regprocedure('public.is_family_admin(uuid)') IS NULL
     OR to_regprocedure('public.check_user_family(uuid)') IS NULL
  THEN
    RAISE EXCEPTION 'FamTrack 11: rode antes os arquivos 01..10 (veja README). Nada foi alterado.';
  END IF;
END $$;

-- Já existe desde o 02; garantido aqui para o caso de banco novo.
CREATE UNIQUE INDEX IF NOT EXISTS uq_families_invite_code
  ON public.families (invite_code);

-- ----------------------------------------------------------------------------
-- 1) A2: escrita direta em family_members / families.
-- ----------------------------------------------------------------------------
-- INSERT: só pelas RPCs (que só inserem o próprio chamador).
DROP POLICY IF EXISTS "family_members_insert" ON public.family_members;
REVOKE INSERT ON public.family_members FROM authenticated, anon;

DROP POLICY IF EXISTS "families_insert" ON public.families;
REVOKE INSERT ON public.families FROM authenticated, anon;

-- UPDATE em families: sem policy já era negado; revogado para ficar explícito.
REVOKE UPDATE ON public.families FROM authenticated, anon;

-- UPDATE em family_members: só as flags de privacidade da própria linha
-- (PrivacyRepository). nickname/avatar_url passam a mudar só via RPC.
REVOKE UPDATE ON public.family_members FROM authenticated, anon;
GRANT UPDATE (sharing_paused, share_health)
  ON public.family_members TO authenticated;

DROP POLICY IF EXISTS "family_members_update_own_flags" ON public.family_members;
CREATE POLICY "family_members_update_own_flags" ON public.family_members
  FOR UPDATE TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- 2) Uma família (aceita) por usuário. Não apaga nada: se houver
--    duplicidade, pula a criação e avisa (veja PRÉ-CHECK 2).
-- ----------------------------------------------------------------------------
DO $$
DECLARE
  v_dups integer;
BEGIN
  IF to_regclass('public.uq_family_members_one_accepted_family') IS NULL THEN
    SELECT count(*) INTO v_dups
    FROM (
      SELECT fm.user_id
      FROM public.family_members fm
      WHERE fm.accepted AND fm.user_id IS NOT NULL
      GROUP BY fm.user_id
      HAVING count(*) > 1
    ) d;

    IF v_dups = 0 THEN
      CREATE UNIQUE INDEX uq_family_members_one_accepted_family
        ON public.family_members (user_id)
        WHERE accepted;
    ELSE
      RAISE WARNING 'FamTrack 11: % usuario(s) em mais de uma familia. Indice "uma familia por usuario" NAO criado. Resolva com o PRE-CHECK 2 do cabecalho e rode este arquivo de novo.', v_dups;
    END IF;
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 3) Tentativas de convite que falharam (limite contra força bruta).
--    Ninguém do app lê ou escreve aqui: só join_family_by_code.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.family_join_attempts (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  attempted_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_family_join_attempts_user_time
  ON public.family_join_attempts (user_id, attempted_at DESC);

ALTER TABLE public.family_join_attempts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.family_join_attempts FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- 4) Remove TODAS as versões existentes das RPCs (qualquer assinatura).
--    Necessário porque CREATE OR REPLACE não troca tipo de retorno nem nome
--    de parâmetro, e versões antigas sobrando confundiriam o PostgREST.
--    (regenerate_invite_code NÃO entra: é recriada com a mesma assinatura.)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN ('create_family', 'join_family_by_code',
                        'get_family_members_display', 'update_my_nickname',
                        'update_my_avatar', 'rename_family')
  LOOP
    EXECUTE format('DROP FUNCTION public.%I(%s)', r.proname, r.args);
  END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- 5) Gerador de código de convite (interno; não exposto ao app).
--    6 caracteres do mesmo alfabeto do app (InviteCode.kt), sem 0/O/1/I:
--    32^6 ≈ 1 bilhão de combinações. Aleatoriedade forte de
--    gen_random_uuid() (bytes 0..5 de um UUID v4 são 100% aleatórios;
--    256 é múltiplo de 32, então não há viés).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.famtrack_new_invite_code()
RETURNS text
LANGUAGE plpgsql
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
  c_alphabet CONSTANT text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_bytes bytea := uuid_send(gen_random_uuid());
  v_code text := '';
BEGIN
  FOR i IN 0..5 LOOP
    v_code := v_code || substr(c_alphabet, (get_byte(v_bytes, i) % 32) + 1, 1);
  END LOOP;
  RETURN v_code;
END;
$$;

REVOKE ALL ON FUNCTION public.famtrack_new_invite_code() FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- 6) create_family(p_name) -> família criada (lista com 1 linha).
--    Cliente: FamilyRepository.createFamily -> decodeSingle<Family>()
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.create_family(p_name text)
RETURNS TABLE (
  id uuid,
  name text,
  creator_id uuid,
  invite_code text,
  created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
#variable_conflict use_column
DECLARE
  v_uid uuid := auth.uid();
  v_name text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
  v_family public.families%ROWTYPE;
  v_try integer := 0;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF length(v_name) < 1 OR length(v_name) > 60 THEN
    RAISE EXCEPTION 'O nome da familia deve ter de 1 a 60 caracteres' USING ERRCODE = '22023';
  END IF;

  -- Serializa create/join do mesmo usuário (dois toques, duas telas...).
  PERFORM pg_advisory_xact_lock(hashtextextended('famtrack:member:' || v_uid::text, 0));

  IF EXISTS (
    SELECT 1 FROM public.family_members fm
    WHERE fm.user_id = v_uid AND fm.accepted
  ) THEN
    RAISE EXCEPTION 'Voce ja participa de uma familia. Saia dela antes de criar outra.'
      USING ERRCODE = 'P0001';
  END IF;

  LOOP
    v_try := v_try + 1;
    BEGIN
      INSERT INTO public.families (name, creator_id, invite_code)
      VALUES (v_name, v_uid, public.famtrack_new_invite_code())
      RETURNING * INTO v_family;
      EXIT;
    EXCEPTION WHEN unique_violation THEN
      -- colisão de código (raríssima): tenta outro
      IF v_try >= 10 THEN
        RAISE EXCEPTION 'Nao foi possivel gerar um codigo de convite. Tente de novo.';
      END IF;
    END;
  END LOOP;

  INSERT INTO public.family_members (family_id, user_id, role, accepted)
  VALUES (v_family.id, v_uid, 'admin', true);

  RETURN QUERY
    SELECT v_family.id, v_family.name, v_family.creator_id,
           v_family.invite_code, v_family.created_at;
END;
$$;

-- ----------------------------------------------------------------------------
-- 7) join_family_by_code(p_invite_code) -> família (lista com 1 linha)
--    ou lista VAZIA se o código não existir.
--    Cliente: FamilyRepository.joinFamilyByCode -> decodeSingle<Family>()
--    (lista vazia => exceção => "Codigo de convite invalido").
--    Por que lista vazia e não erro: um RAISE desfaria o registro da
--    tentativa errada, e o limite de tentativas não funcionaria.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.join_family_by_code(p_invite_code text)
RETURNS TABLE (
  id uuid,
  name text,
  creator_id uuid,
  invite_code text,
  created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
#variable_conflict use_column
DECLARE
  v_uid uuid := auth.uid();
  v_code text;
  v_family public.families%ROWTYPE;
  v_fail_15min integer;
  v_fail_24h integer;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  -- Serializa tentativas do mesmo usuário: requisições em paralelo não
  -- conseguem furar a contagem abaixo.
  PERFORM pg_advisory_xact_lock(hashtextextended('famtrack:member:' || v_uid::text, 0));

  -- Limpeza das tentativas antigas do próprio usuário (barato, indexado).
  DELETE FROM public.family_join_attempts a
  WHERE a.user_id = v_uid
    AND a.attempted_at < now() - interval '24 hours';

  SELECT count(*) FILTER (WHERE a.attempted_at > now() - interval '15 minutes'),
         count(*)
    INTO v_fail_15min, v_fail_24h
  FROM public.family_join_attempts a
  WHERE a.user_id = v_uid;

  IF v_fail_15min >= 10 OR v_fail_24h >= 30 THEN
    RAISE EXCEPTION 'Muitas tentativas com codigo invalido. Aguarde e tente novamente.'
      USING ERRCODE = 'P0001';
  END IF;

  -- Mesma normalização do app (InviteCode.sanitize): maiúsculas, sem
  -- espaços/hífens. Aceita 0/1/O/I por causa de códigos antigos (md5).
  v_code := upper(regexp_replace(coalesce(p_invite_code, ''), '[\s-]', '', 'g'));

  IF v_code ~ '^[A-Z0-9]{6}$' THEN
    SELECT * INTO v_family
    FROM public.families f
    WHERE f.invite_code = v_code;
  END IF;

  IF v_family.id IS NULL THEN
    INSERT INTO public.family_join_attempts (user_id) VALUES (v_uid);
    RETURN;  -- lista vazia
  END IF;

  -- Já é membro aceito DESSA família: idempotente, só devolve a família.
  IF NOT EXISTS (
    SELECT 1 FROM public.family_members fm
    WHERE fm.family_id = v_family.id AND fm.user_id = v_uid AND fm.accepted
  ) THEN
    -- Uma família por usuário.
    IF EXISTS (
      SELECT 1 FROM public.family_members fm
      WHERE fm.user_id = v_uid AND fm.accepted
    ) THEN
      RAISE EXCEPTION 'Voce ja participa de outra familia. Saia dela antes de entrar em uma nova.'
        USING ERRCODE = 'P0001';
    END IF;

    -- Entra como membro (o criador que saiu e volta, volta como admin).
    -- Se havia uma linha pendente antiga (accepted = false), ela é aceita.
    INSERT INTO public.family_members (family_id, user_id, role, accepted)
    VALUES (
      v_family.id,
      v_uid,
      CASE WHEN v_family.creator_id = v_uid THEN 'admin' ELSE 'member' END,
      true
    )
    ON CONFLICT (family_id, user_id) DO UPDATE SET accepted = true;
  END IF;

  RETURN QUERY
    SELECT v_family.id, v_family.name, v_family.creator_id,
           v_family.invite_code, v_family.created_at;
END;
$$;

-- ----------------------------------------------------------------------------
-- 8) get_family_members_display(p_family_id) -> membros aceitos com nome e foto.
--    Cliente: FamilyMemberDisplay(user_id, display_name [NÃO nulo], avatar_url?)
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.get_family_members_display(p_family_id uuid)
RETURNS TABLE (
  user_id uuid,
  display_name text,
  avatar_url text
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
#variable_conflict use_column
DECLARE
  v_uid uuid := auth.uid();
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.family_members me
    WHERE me.family_id = p_family_id AND me.user_id = v_uid AND me.accepted
  ) THEN
    RAISE EXCEPTION 'Voce nao e membro desta familia' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
    SELECT
      fm.user_id,
      coalesce(
        nullif(btrim(fm.nickname), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'name'), ''),
        nullif(split_part(u.email, '@', 1), ''),
        'Membro'
      )::text AS display_name,
      coalesce(
        nullif(btrim(fm.avatar_url), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'avatar_url'), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'picture'), '')
      )::text AS avatar_url
    FROM public.family_members fm
    LEFT JOIN auth.users u ON u.id = fm.user_id
    WHERE fm.family_id = p_family_id
      AND fm.accepted
      AND fm.user_id IS NOT NULL
    ORDER BY fm.created_at;
END;
$$;

-- ----------------------------------------------------------------------------
-- 9) update_my_nickname(p_nickname) — nome no pino do mapa (própria linha).
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.update_my_nickname(p_nickname text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_nick text := regexp_replace(btrim(coalesce(p_nickname, '')), '\s+', ' ', 'g');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF length(v_nick) < 1 OR length(v_nick) > 50 OR v_nick ~ '[[:cntrl:]]' THEN
    RAISE EXCEPTION 'O nome deve ter de 1 a 50 caracteres' USING ERRCODE = '22023';
  END IF;

  UPDATE public.family_members fm
  SET nickname = v_nick
  WHERE fm.user_id = v_uid AND fm.accepted;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Voce ainda nao esta em uma familia' USING ERRCODE = 'P0001';
  END IF;
END;
$$;

-- ----------------------------------------------------------------------------
-- 10) update_my_avatar(p_avatar_url) — foto (própria linha). '' apaga.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.update_my_avatar(p_avatar_url text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_url text := nullif(btrim(coalesce(p_avatar_url, '')), '');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF v_url IS NOT NULL
     AND (length(v_url) > 1024 OR v_url !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'Endereco de foto invalido (use https://)' USING ERRCODE = '22023';
  END IF;

  UPDATE public.family_members fm
  SET avatar_url = v_url
  WHERE fm.user_id = v_uid AND fm.accepted;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Voce ainda nao esta em uma familia' USING ERRCODE = 'P0001';
  END IF;
END;
$$;

-- ----------------------------------------------------------------------------
-- 11) rename_family(p_family_id, p_new_name) — criador ou admin, membro aceito.
--     Cliente trata QUALQUER erro como "Sem permissao para renomear", por
--     isso a negação é sempre por exceção (nunca "sucesso" silencioso).
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.rename_family(p_family_id uuid, p_new_name text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_name text := regexp_replace(btrim(coalesce(p_new_name, '')), '\s+', ' ', 'g');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF length(v_name) < 1 OR length(v_name) > 60 THEN
    RAISE EXCEPTION 'O nome da familia deve ter de 1 a 60 caracteres' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.family_members fm
    JOIN public.families f ON f.id = fm.family_id
    WHERE fm.family_id = p_family_id
      AND fm.user_id = v_uid
      AND fm.accepted
      AND (fm.role = 'admin' OR f.creator_id = v_uid)
  ) THEN
    RAISE EXCEPTION 'Apenas o criador ou um administrador pode renomear a familia'
      USING ERRCODE = '42501';
  END IF;

  UPDATE public.families f
  SET name = v_name
  WHERE f.id = p_family_id;
END;
$$;

-- ----------------------------------------------------------------------------
-- 12) regenerate_invite_code (do 03), mesma assinatura: agora com o gerador
--     forte e tratamento de colisão. Apenas admin aceito.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.regenerate_invite_code(p_family_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_new_code text;
  v_try integer := 0;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Usuario nao autenticado' USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.family_members fm
    WHERE fm.family_id = p_family_id
      AND fm.user_id = v_uid
      AND fm.accepted
      AND fm.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Apenas administradores podem gerar novo codigo de convite'
      USING ERRCODE = '42501';
  END IF;

  LOOP
    v_try := v_try + 1;
    v_new_code := public.famtrack_new_invite_code();
    BEGIN
      UPDATE public.families f
      SET invite_code = v_new_code
      WHERE f.id = p_family_id;
      EXIT;
    EXCEPTION WHEN unique_violation THEN
      IF v_try >= 10 THEN
        RAISE EXCEPTION 'Nao foi possivel gerar um codigo de convite. Tente de novo.';
      END IF;
    END;
  END LOOP;

  RETURN v_new_code;
END;
$$;

-- ----------------------------------------------------------------------------
-- 13) Permissões: nada para anon/PUBLIC; EXECUTE só para authenticated.
--     (O Supabase dá EXECUTE a anon por padrão em funções novas.)
-- ----------------------------------------------------------------------------
REVOKE ALL ON FUNCTION public.create_family(text)                    FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.join_family_by_code(text)              FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.get_family_members_display(uuid)       FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.update_my_nickname(text)               FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.update_my_avatar(text)                 FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.rename_family(uuid, text)              FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.regenerate_invite_code(uuid)           FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.create_family(text)                 TO authenticated;
GRANT EXECUTE ON FUNCTION public.join_family_by_code(text)           TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_family_members_display(uuid)    TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_my_nickname(text)            TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_my_avatar(text)              TO authenticated;
GRANT EXECUTE ON FUNCTION public.rename_family(uuid, text)           TO authenticated;
GRANT EXECUTE ON FUNCTION public.regenerate_invite_code(uuid)        TO authenticated;

COMMIT;

-- Pede ao PostgREST (API do Supabase) para reler as funções agora.
NOTIFY pgrst, 'reload schema';

-- ----------------------------------------------------------------------------
-- VERIFICAÇÃO (esperado: todas as linhas com ok = true).
-- Se "uma familia por usuario" vier false, veja o PRÉ-CHECK 2.
-- ----------------------------------------------------------------------------
SELECT item, ok FROM (
  SELECT 1 AS ordem, 'policy family_members_insert removida' AS item,
         NOT EXISTS (SELECT 1 FROM pg_policies
                     WHERE schemaname = 'public' AND tablename = 'family_members'
                       AND policyname = 'family_members_insert') AS ok
  UNION ALL
  SELECT 2, 'INSERT direto em family_members bloqueado',
         NOT has_table_privilege('authenticated', 'public.family_members', 'INSERT')
  UNION ALL
  SELECT 3, 'INSERT direto em families bloqueado',
         NOT has_table_privilege('authenticated', 'public.families', 'INSERT')
  UNION ALL
  SELECT 4, 'UPDATE de role/accepted/family_id bloqueado',
         NOT has_column_privilege('authenticated', 'public.family_members', 'role', 'UPDATE')
         AND NOT has_column_privilege('authenticated', 'public.family_members', 'accepted', 'UPDATE')
         AND NOT has_column_privilege('authenticated', 'public.family_members', 'family_id', 'UPDATE')
  UNION ALL
  SELECT 5, 'indice uma familia por usuario',
         to_regclass('public.uq_family_members_one_accepted_family') IS NOT NULL
  UNION ALL
  SELECT 6, 'RPCs criadas (7) e sem acesso anon',
         (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public'
            AND p.proname IN ('create_family', 'join_family_by_code',
                              'get_family_members_display', 'update_my_nickname',
                              'update_my_avatar', 'rename_family',
                              'regenerate_invite_code')
            AND p.prosecdef
            AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
            AND NOT has_function_privilege('anon', p.oid, 'EXECUTE')) = 7
) v
ORDER BY ordem;
