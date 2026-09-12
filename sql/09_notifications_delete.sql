-- =============================================================
-- 09_notifications_delete.sql
-- ETAPA 8E (item F): permite ao app excluir notificações
-- (excluir uma / limpar todas). A política vale apenas para
-- linhas do próprio usuário (user_id = auth.uid()); o RLS NÃO é
-- desabilitado de forma alguma.
--
-- EXECUTAR no SQL Editor do Supabase.
-- =============================================================

drop policy if exists "notifications_delete" on public.notifications;

create policy "notifications_delete" on public.notifications
  for delete to authenticated
  using (user_id = auth.uid());

-- Confirmação (esperado: 1 linha)
select policyname, cmd
from pg_policies
where tablename = 'notifications'
  and policyname = 'notifications_delete';