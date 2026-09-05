-- ============================================
-- FamTrack - Migracao de Features (F1 a F8)
-- Execute este SQL no SQL Editor do Supabase
-- (Supabase Dashboard > SQL Editor > New Query)
-- Pode rodar varias vezes sem erro (idempotente)
-- ============================================

-- ============================================
-- 1. FLAGS DE PRIVACIDADE EM family_members (F7)
-- ============================================
alter table public.family_members
  add column if not exists sharing_paused boolean not null default false;

alter table public.family_members
  add column if not exists share_health boolean not null default false;

-- Usuario pode atualizar APENAS a propria linha (flags de privacidade)
drop policy if exists "family_members_update_own_flags" on public.family_members;
create policy "family_members_update_own_flags" on public.family_members
  for update to authenticated
  using (user_id = auth.uid());

-- ============================================
-- 2. TABELA places (F3)
-- ============================================
create table if not exists public.places (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  name text not null,
  type text not null default 'OUTRO' check (type in ('CASA', 'ESCOLA', 'TRABALHO', 'OUTRO')),
  center_lat double precision not null,
  center_lon double precision not null,
  radius_meters integer not null default 150 check (radius_meters >= 100 and radius_meters <= 1000),
  alert_on_enter boolean not null default true,
  alert_on_exit boolean not null default true,
  geofence_id uuid references public.geofences(id) on delete set null,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

create index if not exists idx_places_family on public.places(family_id);

alter table public.places enable row level security;

-- Todos os membros aceitos podem ver os locais da familia
drop policy if exists "places_select" on public.places;
create policy "places_select" on public.places
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

-- Escrita apenas por administradores (espelha a regra de geofences)
drop policy if exists "places_admin_all" on public.places;
create policy "places_admin_all" on public.places
  for all to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and role = 'admin'
    )
  )
  with check (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and role = 'admin'
    )
  );

-- ============================================
-- 3. TABELA events (F6)
-- ============================================
create table if not exists public.events (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  type text not null check (type in ('ENTER', 'EXIT', 'SOS', 'CHECKIN', 'LOW_BATTERY')),
  member_id uuid references auth.users(id) on delete cascade,
  place_id uuid references public.places(id) on delete set null,
  lat double precision not null default 0,
  lng double precision not null default 0,
  created_at timestamptz not null default now()
);

create index if not exists idx_events_family_time
  on public.events(family_id, created_at desc);

create index if not exists idx_events_member_time
  on public.events(member_id, created_at desc);

alter table public.events enable row level security;

drop policy if exists "events_select" on public.events;
create policy "events_select" on public.events
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

-- INSERT anti-spoofing: o evento so pode ser gravado em nome do proprio usuario,
-- membro aceito da familia informada.
drop policy if exists "events_insert" on public.events;
create policy "events_insert" on public.events
  for insert to authenticated
  with check (
    member_id = auth.uid()
    and family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

-- ============================================
-- 4. TABELA health_snapshots (F2/F7)
-- ============================================
create table if not exists public.health_snapshots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  family_id uuid references public.families(id) on delete cascade,
  date date not null,
  bpm_media integer,
  bpm_min integer,
  bpm_max integer,
  samples integer not null default 0,
  created_at timestamptz not null default now(),
  unique(family_id, user_id, date)
);

create index if not exists idx_health_snapshots_family_date
  on public.health_snapshots(family_id, date);

alter table public.health_snapshots enable row level security;

-- O proprio usuario sempre ve. Outros membros da familia so veem se o dono
-- autorizou (share_health = true), respeitando o padrao desligado da F7.
drop policy if exists "health_snapshots_select" on public.health_snapshots;
create policy "health_snapshots_select" on public.health_snapshots
  for select to authenticated
  using (
    (
      family_id in (
        select family_id from public.family_members
        where user_id = auth.uid() and accepted = true
      )
      and (
        user_id = auth.uid()
        or exists (
          select 1 from public.family_members fm_owner
          where fm_owner.user_id = health_snapshots.user_id
            and fm_owner.family_id = health_snapshots.family_id
            and fm_owner.share_health = true
        )
      )
    )
  );

drop policy if exists "health_snapshots_insert" on public.health_snapshots;
create policy "health_snapshots_insert" on public.health_snapshots
  for insert to authenticated
  with check (user_id = auth.uid());

drop policy if exists "health_snapshots_update" on public.health_snapshots;
create policy "health_snapshots_update" on public.health_snapshots
  for update to authenticated
  using (user_id = auth.uid());

-- ============================================
-- 5. RPC regenerate_invite_code (F4)
--    Gera codigo de 6 caracteres SEM 0, O, 1 e I.
--    Apenas administradores da familia podem regenerar.
-- ============================================
create or replace function public.regenerate_invite_code(p_family_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
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
$$;

revoke all on function public.regenerate_invite_code(uuid) from public;
grant execute on function public.regenerate_invite_code(uuid) to authenticated;

-- ============================================
-- 6. REALTIME nas novas tabelas (F5/F6)
--    Idempotente: publica cada tabela apenas se ainda nao estiver publicada.
-- ============================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'events'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.events;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'places'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.places;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'sos_alerts'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.sos_alerts;
  END IF;
END $$;

-- ============================================
-- 7. locations: battery_level e last_updated_at (F2/F7)
--    Colunas e constraints adicionadas de forma idempotente.
-- ============================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'locations' AND column_name = 'battery_level'
  ) THEN
    ALTER TABLE public.locations ADD COLUMN battery_level integer;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'locations' AND column_name = 'last_updated_at'
  ) THEN
    ALTER TABLE public.locations ADD COLUMN last_updated_at bigint;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'locations_battery_level_check'
  ) THEN
    ALTER TABLE public.locations
      ADD CONSTRAINT locations_battery_level_check
      CHECK (battery_level IS NULL OR (battery_level >= 0 AND battery_level <= 100));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'locations_last_updated_at_check'
  ) THEN
    ALTER TABLE public.locations
      ADD CONSTRAINT locations_last_updated_at_check
      CHECK (last_updated_at IS NULL OR last_updated_at >= 0);
  END IF;
END $$;

-- ============================================
-- 8. geofences.place_id: relacao explicita com places (F6)
--    Idempotente.
-- ============================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'geofences' AND column_name = 'place_id'
  ) THEN
    ALTER TABLE public.geofences ADD COLUMN place_id uuid;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'geofences_place_id_fk'
  ) THEN
    ALTER TABLE public.geofences
      ADD CONSTRAINT geofences_place_id_fk
      FOREIGN KEY (place_id) REFERENCES public.places(id) ON DELETE CASCADE;
  END IF;
END $$;

-- Indice unico parcial: no maximo uma geofence por place
create unique index if not exists idx_geofences_place_id_unique
  on public.geofences(place_id) where place_id is not null;