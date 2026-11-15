-- ============================================================
-- Cópia de segurança das RPCs de família como estavam em PRODUÇÃO
-- (projeto wwwqurizwoleqbtozwcz) em 2026-10-18, ANTES de aplicar
-- 11_family_rpcs_and_member_security.sql.
--
-- Para voltar atrás (rollback) de uma função específica: rode o bloco
-- dela no SQL Editor. ATENÇÃO: o 11 muda tipos de retorno; antes de
-- recriar uma destas, rode  DROP FUNCTION public.<nome>(<argumentos>);
-- ============================================================

-- create_family(p_name text) -> SETOF families
CREATE OR REPLACE FUNCTION public.create_family(p_name text)
 RETURNS SETOF families
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_family public.families;
  v_code text;
begin
  if auth.uid() is null then
    raise exception 'NAO_AUTENTICADO';
  end if;

  -- Gera um código de convite único (6 caracteres)
  loop
    v_code := upper(substr(md5(random()::text), 1, 6));
    exit when not exists (select 1 from public.families where invite_code = v_code);
  end loop;

  -- Cria a família (nome sem espaços sobrando)
  insert into public.families (name, creator_id, invite_code)
  values (trim(p_name), auth.uid(), v_code)
  returning * into v_family;

  -- Adiciona o criador como ADMIN aceito (era este passo que falhava)
  insert into public.family_members (family_id, user_id, role, accepted)
  values (v_family.id, auth.uid(), 'admin', true);

  return query select * from public.families where id = v_family.id;
end;
$function$;

-- get_family_members_display(p_family_id uuid) -> TABLE(user_id uuid, display_name text, avatar_url text)
CREATE OR REPLACE FUNCTION public.get_family_members_display(p_family_id uuid)
 RETURNS TABLE(user_id uuid, display_name text, avatar_url text)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select fm.user_id,
         coalesce(
           nullif(fm.nickname, ''),
           u.raw_user_meta_data->>'full_name',
           u.raw_user_meta_data->>'name',
           split_part(u.email, '@', 1),
           left(u.id::text, 8)
         ) as display_name,
         coalesce(
           u.raw_user_meta_data->>'avatar_url',
           u.raw_user_meta_data->>'picture'
         ) as avatar_url
  from public.family_members fm
  join auth.users u on u.id = fm.user_id
  where fm.family_id = p_family_id
    and fm.accepted = true
    and p_family_id in (select public.check_user_family(auth.uid()))
$function$;

-- join_family_by_code(p_invite_code text) -> SETOF families
CREATE OR REPLACE FUNCTION public.join_family_by_code(p_invite_code text)
 RETURNS SETOF families
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_family public.families;
begin
  -- 1) Procura a família pelo código (ignora maiúsculas/minúsculas e espaços)
  select * into v_family
  from public.families
  where upper(invite_code) = upper(trim(p_invite_code));

  if v_family.id is null then
    raise exception 'CODIGO_INVALIDO';
  end if;

  -- 2) Insere o usuário logado como membro aceito (ou reativa se já existia)
  insert into public.family_members (family_id, user_id, role, accepted)
  values (v_family.id, auth.uid(), 'member', true)
  on conflict (family_id, user_id)
  do update set accepted = true;

  -- 3) Devolve a família (o app vai ler este resultado)
  return query select * from public.families where id = v_family.id;
end;
$function$;

-- regenerate_invite_code(p_family_id uuid) -> text
CREATE OR REPLACE FUNCTION public.regenerate_invite_code(p_family_id uuid)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_is_admin boolean;
  v_new_code text;
begin
  select exists (
    select 1 from public.family_members
    where family_id = p_family_id
      and user_id = auth.uid()
      and accepted = true
      and role = 'admin'
  ) into v_is_admin;

  if not v_is_admin then
    raise exception 'Apenas administradores podem gerar novo codigo de convite';
  end if;

  loop
    v_new_code := upper(
      substr(
        regexp_replace(md5(random()::text), '[0O1I]', '', 'g'),
        1, 6
      )
    );
    exit when length(v_new_code) = 6 and not exists (
      select 1 from public.families where invite_code = v_new_code
    );
  end loop;

  update public.families
  set invite_code = v_new_code
  where id = p_family_id;

  return v_new_code;
end;
$function$;

-- rename_family(p_family_id uuid, p_new_name text) -> void
CREATE OR REPLACE FUNCTION public.rename_family(p_family_id uuid, p_new_name text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if nullif(trim(p_new_name), '') is null then
    raise exception 'NOME_VAZIO';
  end if;

  update public.families
  set name = trim(p_new_name)
  where id = p_family_id
    and creator_id = auth.uid();

  if not found then
    raise exception 'SEM_PERMISSAO';
  end if;
end;
$function$;

-- update_my_avatar(p_avatar_url text) -> void
CREATE OR REPLACE FUNCTION public.update_my_avatar(p_avatar_url text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  update public.family_members
  set avatar_url = nullif(trim(p_avatar_url), '')
  where user_id = auth.uid() and accepted = true;
  if not found then
    raise exception 'SEM_FAMILIA';
  end if;
end;
$function$;

-- update_my_nickname(p_nickname text) -> void
CREATE OR REPLACE FUNCTION public.update_my_nickname(p_nickname text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if nullif(trim(p_nickname), '') is null then
    raise exception 'NOME_VAZIO';
  end if;

  update public.family_members
  set nickname = trim(p_nickname)
  where user_id = auth.uid()
    and accepted = true;

  if not found then
    raise exception 'SEM_FAMILIA';
  end if;
end;
$function$;
