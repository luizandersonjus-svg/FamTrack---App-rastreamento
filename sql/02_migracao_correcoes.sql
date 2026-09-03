-- ============================================
-- FamTrack - Migracao de Correcoes (2026)
-- Execute este SQL no SQL Editor do Supabase
-- (Supabase Dashboard > SQL Editor > New Query)
-- Pode rodar varias vezes sem erro (idempotente)
-- ============================================

-- ============================================
-- 1. COLUNA invite_code NA TABELA families
-- ============================================
alter table public.families
  add column if not exists invite_code text;

-- Índice único para busca rápida por código (e evitar duplicatas)
create unique index if not exists uq_families_invite_code
  on public.families(invite_code);

-- Atualizar famílias existentes que não possuem código
update public.families
set invite_code = upper(substr(md5(random()::text), 1, 6))
where invite_code is null or invite_code = '';

-- ============================================
-- 2. CHAVE ÚNICA EM locations (family_id, user_id)
--    Necessária para o UPSERT (última localização por usuário)
-- ============================================
-- Remove eventuais duplicatas antigas mantendo apenas a mais recente (por created_at)
delete from public.locations a
using public.locations b
where a.id <> b.id
  and a.family_id = b.family_id
  and a.user_id = b.user_id
  and (
    a.created_at < b.created_at
    or (a.created_at = b.created_at and a.id < b.id)
  );

-- Cria a restrição única. Se não existir, cria.
do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'locations_family_user_key'
  ) then
    alter table public.locations
      add constraint locations_family_user_key unique (family_id, user_id);
  end if;
end $$;

-- Drop do índice antigo (redundante com a constraint única, apenas organiza)
drop index if exists idx_locations_family_user;

-- ============================================
-- 3. FUNÇÃO SECURITY DEFINER: insert_family_notifications
--    Insere notificações para TODOS os membros da família
--    (exceto o remetente), contornando o RLS de notifications
--    que só permite user_id = auth.uid().
-- ============================================
create or replace function public.insert_family_notifications(
  p_family_id uuid,
  p_sender_user_id uuid,
  p_title text,
  p_message text,
  p_type text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.notifications (family_id, user_id, title, message, type, read)
  select
    fm.family_id,
    fm.user_id,
    p_title,
    p_message,
    p_type,
    false
  from public.family_members fm
  where fm.family_id = p_family_id
    and fm.accepted = true
    and fm.user_id <> p_sender_user_id;

  if not found then
    -- nada a fazer, sem outros membros
    null;
  end if;
end;
$$;

-- Garantir que a função é utilizável por usuários autenticados
revoke all on function public.insert_family_notifications(uuid, uuid, text, text, text) from public;
grant execute on function public.insert_family_notifications(uuid, uuid, text, text, text) to authenticated;
