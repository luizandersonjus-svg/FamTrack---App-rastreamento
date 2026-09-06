-- ============================================================================
-- 04_route_history.sql — Fase 1: histórico de rotas por dia + retenção
-- ============================================================================
-- O que esta migration faz:
--   1) Enriquecer route_history com telemetria opcional (nullable): accuracy,
--      speed, bearing, battery_level e provider. Pontos legados sem esses
--      campos continuam funcionando.
--   2) Adicionar o índice por recorded_at (necessário para consultas por dia
--      e para o expurgo de dados antigos).
--   3) Criar public.purge_route_history(retention_days) — função security
--      definer que remove pontos mais antigos que o período de retenção.
--   4) Agendar automaticamente o expurgo com pg_cron, se a extensão existir.
--
-- RETENÇÃO PADRÃO: 30 dias.
--   * Para alterar a retenção permanente, edite o parâmetro na chamada de
--     cron.schedule abaixo e reexecute esta migration (o job é idempotente).
--   * Para um expurgo manual de teste:
--       select public.purge_route_history(30);
--
-- PG_CRON: se a extensão não estiver habilitada, a migration NÃO falha — apenas
-- emite um aviso. Para habilitar: Supabase Dashboard -> Database -> Extensions
-- -> pg_cron (Enable), depois reexecute esta migration para agendar o job.
--
-- IDEMPOTENTE: pode ser executada N vezes sem erro.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Colunas novas (todas nullable; pontos legados seguem funcionando)
-- ----------------------------------------------------------------------------
alter table public.route_history
    add column if not exists accuracy real;

alter table public.route_history
    add column if not exists speed real;

alter table public.route_history
    add column if not exists bearing real;

alter table public.route_history
    add column if not exists battery_level integer;

alter table public.route_history
    add column if not exists provider text;

-- ----------------------------------------------------------------------------
-- 2) Check constraints nomeadas (criadas apenas se não existirem; toleram NULL)
-- ----------------------------------------------------------------------------
do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'route_history_battery_level_check'
          and conrelid = 'public.route_history'::regclass
    ) then
        alter table public.route_history
            add constraint route_history_battery_level_check
            check (battery_level between 0 and 100);
    end if;
end $$;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'route_history_accuracy_check'
          and conrelid = 'public.route_history'::regclass
    ) then
        alter table public.route_history
            add constraint route_history_accuracy_check
            check (accuracy >= 0);
    end if;
end $$;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'route_history_speed_check'
          and conrelid = 'public.route_history'::regclass
    ) then
        alter table public.route_history
            add constraint route_history_speed_check
            check (speed >= 0);
    end if;
end $$;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'route_history_bearing_check'
          and conrelid = 'public.route_history'::regclass
    ) then
        alter table public.route_history
            add constraint route_history_bearing_check
            check (bearing between 0 and 360);
    end if;
end $$;

-- ----------------------------------------------------------------------------
-- 3) Índice por recorded_at (consultas por dia e expurgo)
-- ----------------------------------------------------------------------------
create index if not exists idx_route_history_recorded_at
    on public.route_history (recorded_at);

-- ----------------------------------------------------------------------------
-- 4) Função de retenção (security definer; clientes NÃO podem chamar)
-- ----------------------------------------------------------------------------
create or replace function public.purge_route_history(
    retention_days integer default 30
) returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    removed integer;
begin
    delete from public.route_history
    where recorded_at < now() - (retention_days || ' days')::interval;
    get diagnostics removed = row_count;
    return removed;
end;
$$;

-- Apenas o cron (postgres) executa o expurgo.
revoke execute on function public.purge_route_history(integer) from public, anon, authenticated;

-- ----------------------------------------------------------------------------
-- 5) Agendamento com pg_cron (guardado; nunca falha por ausência da extensão)
-- ----------------------------------------------------------------------------
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        if not exists (
            select 1 from cron.job where jobname = 'famtrack_purge_route_history'
        ) then
            perform cron.schedule(
                'famtrack_purge_route_history',
                '15 3 * * *',
                'select public.purge_route_history(30);'
            );
        end if;
    else
        raise notice
            'FamTrack: pg_cron não está habilitado — habilite em Dashboard -> Database -> Extensions -> pg_cron e reexecute esta migration para agendar o expurgo automático.';
    end if;
exception when others then
    raise notice
        'FamTrack: não foi possível agendar o expurgo (pg_cron indisponível?): %', sqlerrm;
end $$;