-- ============================================
-- FamTrack - Setup Completo do Supabase
-- Copie e cole no SQL Editor do Supabase
-- (Supabase Dashboard > SQL Editor > New Query)
-- Pode rodar varias vezes sem erro
-- ============================================

-- 1. CRIAR TABELAS
create table if not exists public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  creator_id uuid references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.family_members (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('admin', 'member')),
  nickname text,
  avatar_url text,
  accepted boolean not null default false,
  created_at timestamptz not null default now(),
  unique(family_id, user_id)
);

create table if not exists public.locations (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  accuracy double precision,
  speed double precision,
  bearing double precision,
  created_at timestamptz not null default now()
);

create table if not exists public.geofences (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  name text not null,
  center_lat double precision not null,
  center_lon double precision not null,
  radius_meters double precision not null default 100,
  color text default '#FF0000',
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.sos_alerts (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  message text default 'SOS acionado!',
  resolved boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  title text not null,
  message text not null,
  type text not null check (type in ('geofence', 'sos', 'system')),
  read boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.route_history (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  recorded_at timestamptz not null default now()
);

-- 2. INDICES
create index if not exists idx_locations_family_user on public.locations(family_id, user_id);
create index if not exists idx_locations_created_at on public.locations(created_at desc);
create index if not exists idx_geofences_family on public.geofences(family_id);
create index if not exists idx_sos_alerts_family on public.sos_alerts(family_id, resolved);
create index if not exists idx_route_history_user_time on public.route_history(user_id, recorded_at desc);

-- 3. HABILITAR RLS
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.locations enable row level security;
alter table public.geofences enable row level security;
alter table public.sos_alerts enable row level security;
alter table public.notifications enable row level security;
alter table public.route_history enable row level security;

-- 4. REMOVER POLICIES ANTIGAS (se existirem)
drop policy if exists "families_select" on public.families;
drop policy if exists "families_insert" on public.families;
drop policy if exists "family_members_select" on public.family_members;
drop policy if exists "family_members_insert" on public.family_members;
drop policy if exists "locations_select" on public.locations;
drop policy if exists "locations_insert" on public.locations;
drop policy if exists "locations_update" on public.locations;
drop policy if exists "geofences_select" on public.geofences;
drop policy if exists "geofences_all" on public.geofences;
drop policy if exists "sos_alerts_select" on public.sos_alerts;
drop policy if exists "sos_alerts_insert" on public.sos_alerts;
drop policy if exists "notifications_select" on public.notifications;
drop policy if exists "notifications_insert" on public.notifications;
drop policy if exists "notifications_update" on public.notifications;
drop policy if exists "route_history_select" on public.route_history;
drop policy if exists "route_history_insert" on public.route_history;

-- 5. CRIAR POLICIES NOVAS

-- FAMILIAS
create policy "families_select" on public.families
  for select to authenticated
  using (
    id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "families_insert" on public.families
  for insert to authenticated
  with check (creator_id = auth.uid());

-- MEMBROS
create policy "family_members_select" on public.family_members
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "family_members_insert" on public.family_members
  for insert to authenticated
  with check (
    family_id in (
      select id from public.families
      where creator_id = auth.uid()
    )
  );

-- LOCALIZACOES
create policy "locations_select" on public.locations
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "locations_insert" on public.locations
  for insert to authenticated
  with check (user_id = auth.uid());

create policy "locations_update" on public.locations
  for update to authenticated
  using (user_id = auth.uid());

-- GEOFENCES
create policy "geofences_select" on public.geofences
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "geofences_all" on public.geofences
  for all to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and role = 'admin'
    )
  );

-- SOS
create policy "sos_alerts_select" on public.sos_alerts
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "sos_alerts_insert" on public.sos_alerts
  for insert to authenticated
  with check (user_id = auth.uid());

-- NOTIFICACOES
create policy "notifications_select" on public.notifications
  for select to authenticated
  using (user_id = auth.uid());

create policy "notifications_insert" on public.notifications
  for insert to authenticated
  with check (user_id = auth.uid());

create policy "notifications_update" on public.notifications
  for update to authenticated
  using (user_id = auth.uid());

-- ROTAS
create policy "route_history_select" on public.route_history
  for select to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

create policy "route_history_insert" on public.route_history
  for insert to authenticated
  with check (user_id = auth.uid());

-- 6. HABILITAR REALTIME ( ignora erro se ja existir )
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'locations'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.locations;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'sos_alerts'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.sos_alerts;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'notifications'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
  END IF;
END $$;
