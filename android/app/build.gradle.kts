import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.famtrack.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.famtrack.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Carregar chaves do local.properties
        val localProps = project.rootProject.file("local.properties")
        if (localProps.exists()) {
            val props = Properties().apply { localProps.inputStream().use { load(it) } }
            buildConfigField("String", "SUPABASE_URL", "\"${props.getProperty("SUPABASE_URL", "")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${props.getProperty("SUPABASE_ANON_KEY", "")}\"")
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${props.getProperty("GOOGLE_MAPS_API_KEY", "")}\"")
            manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = props.getProperty("GOOGLE_MAPS_API_KEY", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.07.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.2")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // Supabase (SDK Kotlin oficial - v3.x)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.1"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    // Ktor (required by supabase-kt) — 3.4.1+ corrige KTOR-9348: decoders base64
    // aceitam padding opcional, evitando crash do realtime com JWT sem padding.
    implementation("io.ktor:ktor-client-cio:3.4.1")

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:19.1.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
    implementation("com.google.maps.android:maps-compose:6.4.0")

    // Google Sign-In (Credential Manager)
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Coil (para imagens/avatars)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // DataStore (para preferências)
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")

    // WorkManager (para tarefas em background)
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Room — fila offline de pontos de rota (ETAPA 5)
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
