plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.cesar.bocana"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cesar.bocana"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "5.1.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        flavorDimensions += "environment"

        productFlavors {
            // CORRECTO: Usar create("nombre") para cada flavor
            create("prod") {
                dimension = "environment"
                applicationId = "com.cesar.bocana"
                versionNameSuffix = "-prod"
            }
            create("dev") {
                dimension = "environment"
                applicationId = "com.cesar.bocana.dev"
                versionNameSuffix = "-dev"
            }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // Habilitar Desugaring para usar APIs modernas en versiones de Android antiguas
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // --- Core y UI de AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)

    // --- Arquitectura (Lifecycle, ViewModel, Fragment) ---
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx) // Necesario para 'by viewModels()'

    // --- Base de Datos Local (Room) ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // --- Paging 3 (Listas paginadas) ---
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.room.paging) // Integración de Room con Paging 3

    // --- Firebase (BoM - Bill of Materials) ---
    implementation(platform(libs.firebase.bom)) // El BoM gestiona las versiones de las librerías de Firebase
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.analytics.ktx)
    implementation(libs.play.services.auth) // Para Google Sign-In
    implementation(libs.kotlinx.coroutines.play.services)

    // --- Utilidades ---
    implementation(libs.zxing.android.embedded)
    implementation(libs.lottie)
    implementation(libs.itext7.core)
    implementation(libs.gson) // Para los Type Converters de Room

    // --- Desugaring ---
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
