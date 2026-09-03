# FamTrack - App de Rastreamento Familiar

App Android nativo para rastreamento de localização familiar em tempo real.

## Funcionalidades

- **Login**: Google e Email/Senha
- **Mapa em tempo real**: Veja a posição de todos os familiares
- **Botão SOS**: Alerta rápido em emergências
- **Geofence**: Áreas protegidas com notificações
- **Histórico**: Registro de rotas
- **Notificações**: Alertas de SOS e geofences

## Tecnologias

- **Android**: Kotlin + Jetpack Compose
- **Backend**: Supabase (PostgreSQL + Auth + Realtime)
- **Mapas**: Google Maps SDK
- **Localização**: FusedLocationProvider

## Como Usar

### 1. Configure o Supabase

1. Crie uma conta em [supabase.com](https://supabase.com)
2. Crie um novo projeto
3. Execute o SQL em `sql/01_schema.sql` no SQL Editor
4. Copie a URL e a anon key

### 2. Configure o Google Cloud

1. Acesse [console.cloud.google.com](https://console.cloud.google.com)
2. Crie um projeto
3. Ative a APIs: Maps SDK for Android
4. Crie credenciais OAuth 2.0 (Android)
5. Copie a Web Client ID

### 3. Configure o App

1. Abra o Android Studio
2. Abra a pasta `android/`
3. Crie o arquivo `local.properties` na raiz:

```properties
SUPABASE_URL=https://seu-projeto.supabase.co
SUPABASE_ANON_KEY=sua-chave-anon
GOOGLE_MAPS_API_KEY=sua-chave-google-maps
```

4. Substitua `SEU_WEB_CLIENT_ID_AQUI` em `LoginScreen.kt` pelo Web Client ID

### 4. Rode o App

1. Conecte um celular via USB
2. Clique no botão ▶️ (Run)

## Estrutura do Projeto

```
famtrack/
├── sql/
│   └── 01_schema.sql          # Schema do banco de dados
├── android/
│   ├── app/
│   │   ├── build.gradle.kts   # Dependências do app
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/famtrack/app/
│   │           ├── FamTrackApp.kt
│   │           ├── MainActivity.kt
│   │           ├── data/
│   │           │   ├── model/      # Modelos de dados
│   │           │   └── remote/     # Repositórios (Supabase)
│   │           ├── ui/
│   │           │   ├── theme/      # Tema do app
│   │           │   ├── auth/       # Telas de login/cadastro
│   │           │   ├── home/       # Tela principal (mapa)
│   │           │   ├── geofence/   # Gerenciamento de geofences
│   │           │   ├── history/    # Histórico de rotas
│   │           │   ├── notifications/
│   │           │   └── settings/
│   │           ├── service/        # Serviço de localização
│   │           └── util/           # Utilitários
│   └── build.gradle.kts       # Dependências do projeto
├── PLANO.md                   # Plano detalhado
└── SETUP.md                   # Guia de setup
```

## Permissões

- `ACCESS_FINE_LOCATION`: Localização precisa
- `ACCESS_COARSE_LOCATION`: Localização aproximada
- `ACCESS_BACKGROUND_LOCATION`: Localização em segundo plano
- `FOREGROUND_SERVICE`: Serviço em foreground
- `POST_NOTIFICATIONS`: Notificações (Android 13+)

## Custos

| Item | Custo |
|------|-------|
| Android Studio | Grátis |
| GitHub | Grátis |
| Supabase (Hobby) | Grátis |
| Google Maps SDK | Grátis (até 28.500 carregamentos/mês) |
| Google Play Console | $25 (opcional) |

## Próximos Passos

- [ ] Implementar compartilhamento de família (convites)
- [ ] Adicionar chat entre membros
- [ ] Implementar modo offline
- [ ] Adicionar suporte a iOS (Flutter/React Native)
- [ ] Publicar na Google Play Store

## Licença

MIT
