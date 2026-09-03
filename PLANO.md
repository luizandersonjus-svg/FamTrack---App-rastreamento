# FamTrack - Plano de Implementação

## 📋 Resumo do Projeto

App Android nativo de rastreamento familiar com:
- Login (Google ou email/senha)
- Mapa com posições em tempo real
- Botão SOS
- Geofence (áreas protegidas)
- Histórico de rotas
- Notificações

---

## 🔧 Correções da Proposta Arena AI

| Erro no Arena AI | Correção |
|------------------|----------|
| `com.supabase.supabase-js:2.15.0` | SDK correto: `io.github.jan-tennert.supabase:2.0.0` |
| `require('geolib')` nas Edge Functions | Supabase usa **Deno**, não Node.js. Usar `import` do Deno |
| Código mistura JavaScript com Kotlin | Todo código Android deve ser **Kotlin puro** |
| `supabase.from("locations").stream("realtime")` | API correta: `supabase.realtime.channel("locations").postgresChanges(...)` |
| Não menciona Google Maps API Key | Precisa criar projeto no Google Cloud e gerar chave |

---

## 🗄️ Schema do Banco de Dados (Supabase)

### Tabelas Principais

```sql
-- 1. FAMÍLIAS
create table if not exists public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  creator_id uuid references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

-- 2. MEMBROS DA FAMÍLIA
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

-- 3. LOCALIZAÇÕES (atualizada em tempo real)
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

-- 4. GEOFENCES (áreas protegidas)
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

-- 5. ALERTAS SOS
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

-- 6. NOTIFICAÇÕES
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

-- 7. HISTÓRICO DE ROTAS
create table if not exists public.route_history (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  recorded_at timestamptz not null default now()
);
```

### Índices para Performance

```sql
-- Índices para consultas frequentes
create index if not exists idx_locations_family_user
  on public.locations(family_id, user_id);

create index if not exists idx_locations_created_at
  on public.locations(created_at desc);

create index if not exists idx_geofences_family
  on public.geofences(family_id);

create index if not exists idx_sos_alerts_family
  on public.sos_alerts(family_id, resolved);

create index if not exists idx_route_history_user_time
  on public.route_history(user_id, recorded_at desc);
```

### RLS (Row Level Security)

```sql
-- Habilitar RLS em todas as tabelas
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.locations enable row level security;
alter table public.geofences enable row level security;
alter table public.sos_alerts enable row level security;
alter table public.notifications enable row level security;
alter table public.route_history enable row level security;

-- Policies: membros só veem dados da própria família
create policy "families_select" on public.families
  for select to authenticated
  using (
    id in (
      select family_id from public.family_members
      where user_id = auth.uid() and accepted = true
    )
  );

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

create policy "geofences_all" on public.geofences
  for all to authenticated
  using (
    family_id in (
      select family_id from public.family_members
      where user_id = auth.uid() and role = 'admin'
    )
  );

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

create policy "notifications_select" on public.notifications
  for select to authenticated
  using (user_id = auth.uid());
```

### Trigger para Updated At

```sql
-- Função genérica para updated_at
create or replace function public.handle_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;
```

---

## 📱 Arquitetura do App Android

### Estrutura de Pastas

```
app/src/main/java/com/famtrack/
├── FamTrackApp.kt                    # Application class
├── MainActivity.kt                   # Activity principal
├── data/
│   ├── model/
│   │   ├── User.kt                   # Modelo de usuário
│   │   ├── Family.kt                 # Modelo de família
│   │   ├── Location.kt               # Modelo de localização
│   │   ├── Geofence.kt               # Modelo de geofence
│   │   ├── SosAlert.kt              # Modelo de alerta SOS
│   │   └── Notification.kt           # Modelo de notificação
│   ├── remote/
│   │   ├── SupabaseClient.kt         # Configuração do Supabase
│   │   ├── AuthRepository.kt         # Autenticação
│   │   ├── FamilyRepository.kt       # Operações de família
│   │   ├── LocationRepository.kt     # Localização
│   │   └── GeofenceRepository.kt     # Geofences
│   └── local/
│       └── LocationCache.kt          # Cache local de posições
├── ui/
│   ├── theme/
│   │   ├── Color.kt                  # Cores do app
│   │   ├── Theme.kt                  # Tema Material3
│   │   └── Type.kt                   # Tipografia
│   ├── auth/
│   │   ├── LoginScreen.kt            # Tela de login
│   │   ├── RegisterScreen.kt         # Tela de cadastro
│   │   └── AuthViewModel.kt          # ViewModel de auth
│   ├── home/
│   │   ├── HomeScreen.kt             # Tela principal (mapa)
│   │   ├── HomeViewModel.kt          # ViewModel principal
│   │   └── FamilyListDrawer.kt       # Drawer com lista de membros
│   ├── map/
│   │   ├── MapScreen.kt              # Tela do mapa
│   │   └── MapViewModel.kt           # ViewModel do mapa
│   ├── sos/
│   │   ├── SosButton.kt              # Botão SOS
│   │   └── SosViewModel.kt           # ViewModel do SOS
│   ├── geofence/
│   │   ├── GeofenceScreen.kt         # Tela de geofences
│   │   ├── GeofenceViewModel.kt      # ViewModel de geofences
│   │   └── GeofenceSetupScreen.kt    # Criar/editar geofence
│   ├── history/
│   │   ├── HistoryScreen.kt          # Histórico de rotas
│   │   └── HistoryViewModel.kt       # ViewModel de histórico
│   ├── notifications/
│   │   ├── NotificationsScreen.kt    # Lista de notificações
│   │   └── NotificationsViewModel.kt # ViewModel de notificações
│   └── settings/
│       ├── SettingsScreen.kt         # Configurações
│       └── SettingsViewModel.kt      # ViewModel de config
├── service/
│   ├── LocationService.kt            # Service de localização em background
│   └── GeofenceService.kt            # Service de geofence
└── util/
    ├── Constants.kt                  # Constantes
    ├── Permissions.kt                # Utilitário de permissões
    └── Extensions.kt                 # Extensões Kotlin
```

### Dependências (build.gradle.kts - app)

```kotlin
dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Supabase (SDK Kotlin oficial)
    implementation("io.github.jan-tennert.supabase:supabase-kt:2.0.0")
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.0.0")  // Auth
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.0")  // Database
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.0.0")  // Realtime

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Coil (para imagens/avatars)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore (para preferências)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager (para tarefas em background)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

### Dependências (build.gradle.kts - project)

```kotlin
// No build.gradle.kts (project level)
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.18" apply false
}
```

---

## 🔄 Fluxo de Dados em Tempo Real

### Como funciona o Realtime do Supabase no Android

```kotlin
// 1. Conectar ao canal de realtime
val channel = supabase.realtime.channel("locations-channel")

// 2. Escutar mudanças na tabela locations
val postgrestChanges = channel.postgresChangeFlow<PostgresAction>(
    schema = "public"
) {
    table = "locations"
    filter = "family_id=eq.$familyId"
}

// 3. Coletar as mudanças
lifecycleScope.launch {
    postgrestChanges.collect { action ->
        when (action) {
            is PostgresAction.Insert -> {
                val location = action.record.decode<Location>()
                // Atualizar pin no mapa
                updateMapMarker(location)
            }
            is PostgresAction.Update -> {
                val location = action.record.decode<Location>()
                // Mover pin existente
                moveMapMarker(location)
            }
            is PostgresAction.Delete -> {
                // Remover pin do mapa
            }
        }
    }
}

// 4. Subscrever ao canal
channel.subscribe()
```

---

## 🗺️ Tela do Mapa (Exemplo)

```kotlin
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AndroidView(
        factory = { context ->
            val mapFragment = SupportMapFragment.newInstance(
                GoogleMapOptions().apply {
                    mapType(GoogleMap.MAP_TYPE_NORMAL)
                    zoomControlsEnabled(true)
                }
            )
            mapFragment.getMapAsync { googleMap ->
                viewModel.googleMap = googleMap
                googleMap.uiSettings.apply {
                    isMyLocationButtonEnabled = true
                    isZoomControlsEnabled = true
                    isCompassEnabled = true
                }
            }
            mapFragment
        },
        modifier = Modifier.fillMaxSize()
    )

    // Atualizar marcadores quando locations mudar
    LaunchedEffect(locations) {
        viewModel.updateMarkers(locations)
    }
}
```

---

## 🔐 Autenticação com Supabase

```kotlin
// Login com Google
suspend fun signInWithGoogle(idToken: String): AuthResponse {
    return supabase.auth.signInWith(IDToken) {
        idToken = idToken
        provider = Google
    }
}

// Login com email/senha
suspend fun signInWithEmail(email: String, password: String): AuthResponse {
    return supabase.auth.signInWith(Email) {
        this.email = email
        this.password = password
    }
}

// Observar estado de autenticação
val authState = supabase.auth.sessionStatus
```

---

## 📍 Serviço de Localização em Background

```kotlin
class LocationService : LifecycleService() {

    private lateinit var locationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Criar notificação persistente (obrigatório para Android 8+)
        startForeground(NOTIFICATION_ID, createNotification())

        // Iniciar atualizações de localização
        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L  // 10 segundos
        ).apply {
            setMinUpdateDistanceMeters(10f)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()

        locationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                // Enviar para Supabase
                sendLocationToSupabase(location)
            }
        }
    }

    private suspend fun sendLocationToSupabase(location: Location) {
        supabase.from("locations").insert(
            mapOf(
                "family_id" to currentFamilyId,
                "user_id" to currentUserId,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracy" to location.accuracy,
                "speed" to location.speed,
                "bearing" to location.bearing
            )
        )
    }
}
```

---

## ⚠️ Permissões Necessárias (AndroidManifest.xml)

```xml
<!-- Localização -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Serviço em foreground -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<!-- Notificações -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Internet (para Supabase) -->
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 📦 Passos de Implementação

### Fase 1: Setup Inicial
1. Instalar Android Studio
2. Criar novo projeto "Empty Activity" (Kotlin + Compose)
3. Configurar `build.gradle.kts` com dependências
4. Criar conta no Supabase (gratuito)
5. Criar projeto no Supabase
6. Criar tabelas no SQL Editor do Supabase
7. Habilitar Realtime na tabela `locations`
8. Configurar Auth (Google + Email)

### Fase 2: Autenticação
1. Implementar tela de login
2. Implementar tela de cadastro
3. Configurar Supabase Auth no app
4. Testar login com Google e email

### Fase 3: Mapa e Localização
1. Configurar Google Maps SDK
2. Implementar tela do mapa
3. Implementar serviço de localização
4. Enviar localização ao Supabase
5. Receber localização em tempo real (Realtime)

### Fase 4: Funcionalidades
1. Implementar botão SOS
2. Implementar geofences
3. Implementar notificações
4. Implementar histórico de rotas

### Fase 5: Polimento
1. UI/UX refinada
2. Tratamento de erros
3. Testes em dispositivos reais
4. Preparar para publicação

---

## 💰 Custos

| Item | Custo |
|------|-------|
| Android Studio | Grátis |
| GitHub | Grátis |
| Supabase (Hobby) | Grátis (5GB banco, 1GB storage) |
| Google Maps SDK | Grátis (até 28.500 carregamentos/mês) |
| Google Play Console | $25 (pagamento único, só se publicar) |

**Total: $25 (opcional)**

---

## 🎯 Próximos Passos Imediatos

1. **Baixar Android Studio**: https://developer.android.com/studio
2. **Criar conta Supabase**: https://supabase.com
3. **Seguir este plano passo a passo**

---

*Plano atualizado em: $(date)*
