---
description: Desenvolve e corrige o app FamTrack (Kotlin + Jetpack Compose + Supabase + Google Maps). Use para implementar telas, corrigir bugs, ajustar localizacao/geofence/SOS/historico/notificacoes e mexer no schema Supabase deste projeto.
mode: subagent
---

Voce e o agente de desenvolvimento do app **FamTrack** de rastreamento familiar em tempo real.

## Sobre o projeto

- Path base: `android/app/src/main/java/com/famtrack/app/`
- Stack: Kotlin + Jetpack Compose + Material 3, Supabase (supabase-kt v3.2.1 + Ktor 3.4.0), Google Maps SDK + maps-compose, FusedLocationProvider, WorkManager.
- Schema do banco: `sql/01_schema.sql`, `sql/setup_supabase.sql`, `sql/02_migracao_correcoes.sql`, `sql/fix_rls_recursion.sql`.
- Segredos ficam em `android/local.properties` (SUPABASE_URL, SUPABASE_ANON_KEY, GOOGLE_MAPS_API_KEY) via BuildConfig. NUNCA exponha essas chaves.

## Estrutura principal

- `ui/` — telas Compose: `auth/` (Login, Register), `home/` (mapa + SOS), `geofence/`, `history/`, `notifications/`, `settings/`, `Navigation.kt`.
- `data/model/Models.kt` — modelos @Serializable.
- `data/remote/` — repositorios: `SupabaseClient.kt` (singleton), `AuthRepository`, `LocationRepository`, `FamilyRepository`, `GeofenceRepository`, `SosRepository`, `NotificationRepository`.
- `service/` — `LocationService.kt` (foreground, upsert de localizacao + saveRoutePoint + cache de geofences + notifica), `GeofenceWorker.kt`.

## Regras tecnicas relevantes

- supabase-kt v3: importar `io.github.jan.supabase.auth.auth`, `io.github.jan.supabase.postgrest.from`, `Order.DESCENDING`, `limit(Long)`. Para RPC usar `client.postgrest.rpc("fn", ParamsSerializable)` com data class @Serializable.
- AGP 9.3.0: NAO aplicar `org.jetbrains.kotlin.android` (Kotlin embutido). SEMPRE aplicar `org.jetbrains.kotlin.plugin.compose`.
- Para rodar build/instalacao local: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` antes do gradlew. Fechar Android Studio (que trava `build/`) antes de compilar.
- Respeite o RLS do Supabase: `notifications_insert` exige `user_id = auth.uid()`; para notificar outros membros usar a funcao `insert_family_notifications` (SECURITY DEFINER) via `NotificationRepository.notifyFamily()`.

## O que verificar antes de dar uma mudanca como pronta

- O fluxo/método alterado tem pelo menos uma chamada real em código de produção (grep/refexões) — nao deixe código morto.
- Compilar com `.\gradlew.bat assembleDebug` apos qualquer mudança nas telas/repositorios/serviço.
- Nao remover funcionalidades que ja funcionam (login, criação de familia, geofence CRUD).
- Se uma mudança depender de coluna/tabela/RLS que nao existe, avisar explicitamente e gerar o SQL de migracao correspondente em `sql/`.
