# FamTrack - Guia de Setup

## Passo 1: Criar Projeto no Supabase

1. Acesse https://supabase.com e faça login
2. Clique em **"New Project"**
3. Preencha:
   - **Nome**: `famtrack` (ou o que preferir)
   - **Database Password**: crie uma senha forte (anote!)
   - **Region**: Escolha a mais próxima (ex: `South America (São Paulo)`)
4. Clique em **"Create new project"**
5. Aguarde ~2 minutos para o projeto ser criado

## Passo 2: Copiar Credenciais

1. No painel do projeto, vá em **Settings** (ícone de engrenagem)
2. Clique em **API**
3. Copie e salve em um lugar seguro:
   - **Project URL** (ex: `https://xyzcompany.supabase.co`)
   - **anon public** key (eyJhbGciOiJIUzI1NiIs...)

## Passo 3: Criar as Tabelas

1. No painel do Supabase, vá em **SQL Editor**
2. Clique em **"New query"**
3. Cole TODO o conteúdo do arquivo `sql/01_schema.sql`
4. Clique em **"Run"** (botão verde)
5. Aguarde a mensagem de sucesso

## Passo 4: Habilitar Auth (Login)

1. No painel, vá em **Authentication** → **Providers**
2. **Email**:
   - Ative a opção **"Enable Email confirmations"** (ou desative para testes)
3. **Google**:
   - Clique em **Google**
   - Ative o provider
   - Você precisará criar um projeto no Google Cloud Console

### Configurar Google Auth

1. Acesse https://console.cloud.google.com
2. Crie um novo projeto (ou selecione existente)
3. Vá em **APIs & Services** → **Credentials**
4. Clique em **Create Credentials** → **OAuth client ID**
5. Preencha:
   - **Application type**: Android
   - **Package name**: `com.famtrack.app`
   - **SHA-1 certificate fingerprint**: (obter do Android Studio - ver abaixo)

### Obter SHA-1 do Android Studio

1. Abra o Android Studio
2. Vá em **View** → **Tool Windows** → **Gradle**
3. No painel Gradle, expanda: `app` → `Tasks` → `android`
4. Double-click em `signingReport`
5. Copie o SHA-1 que aparece no console

6. Volte ao Google Cloud Console e cole o SHA-1
7. Clique em **Create**
8. Copie o **Client ID** e **Web Client ID**
9. Volte ao Supabase → Authentication → Providers → Google
10. Cole o **Client ID** e **Client Secret** (do Web Client)

## Passo 5: Criar o App no Android Studio

1. Abra o Android Studio
2. Clique em **"New Project"**
3. Selecione **"Empty Activity"** (com Compose)
4. Preencha:
   - **Name**: FamTrack
   - **Package name**: `com.famtrack.app`
   - **Save location**: Escolha uma pasta
   - **Language**: Kotlin
   - **Minimum SDK**: API 26 (Android 8.0)
5. Clique em **Finish**

## Passo 6: Configurar o Projeto

1. No Android Studio, abra o arquivo `app/build.gradle.kts`
2. Substitua TODO o conteúdo pelo arquivo `android/app/build.gradle.kts` deste projeto
3. Clique em **"Sync Now"** (botão no topo)

4. Abra o arquivo `build.gradle.kts` (project level)
5. Substitua pelo arquivo `android/build.gradle.kts` deste projeto
6. Clique em **"Sync Now"** novamente

## Passo 7: Copiar os Fontes

1. No Android Studio, navegue até `app/src/main/java/com/famtrack/`
2. Copie todos os arquivos da pasta `android/app/src/main/java/com/famtrack/` deste projeto
3. Substitua os arquivos existentes

## Passo 8: Configurar Chaves

1. Crie o arquivo `local.properties` na raiz do projeto (nao versionado)
2. Adicione:

```properties
SUPABASE_URL=https://SEU-PROJETO.supabase.co
SUPABASE_ANON_KEY=sua-chave-anon-aqui
GOOGLE_MAPS_API_KEY=sua-chave-google-maps-aqui
```

3. Para obter a chave do Google Maps:
   - No Google Cloud Console (mesmo projeto do Auth)
   - Vá em **APIs & Services** → **Library**
   - Busque por **Maps SDK for Android**
   - Clique em **Enable**
   - Vá em **Credentials** → **Create Credentials** → **API key**
   - Copie a chave

## Passo 9: Testar

1. Conecte um celular via USB (com depuração USB ativada)
2. No Android Studio, clique no botão ▶️ (Run)
3. Selecione seu dispositivo
4. Aguarde o app instalar e abrir

---

## Checklist

- [ ] Projeto Supabase criado
- [ ] Credenciais copiadas (URL + anon key)
- [ ] Tabelas criadas (SQL rodou sem erro)
- [ ] Auth Email habilitado
- [ ] Auth Google configurado
- [ ] Android Studio instalado
- [ ] Projeto Android criado
- [ ] Dependências configuradas (Sync Now)
- [ ] Código copiado
- [ ] Chaves configuradas
- [ ] App rodando no celular

---

*Dúvidas? Me avise em qual passo está travado!*
