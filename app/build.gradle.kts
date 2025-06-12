plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.cesar.bocana" // Tu namespace/paquete
    compileSdk = 34 // O la versión que uses

    defaultConfig {
        applicationId = "com.cesar.bocana"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Definir el web client id (para Google Sign In). Reemplaza el valor!
        // IMPORTANTE: ¡No pongas tu ID real directamente aquí si subes esto a un repo público!
        // Es mejor leerlo desde un archivo no rastreado o variables de entorno en CI/CD.
        // Por ahora, para desarrollo local, lo ponemos aquí, pero ten cuidado.
        // El valor lo encuentras en tu google-services.json o en la consola de Google Cloud / Firebase.
        resValue("string", "default_web_client_id", "804757359228-o1kqe254ml8cfi9pd4fmeq9accvt2v4t.apps.googleusercontent.com")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Cambiar a true para producción
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true // Habilita View Binding
        // compose = true // Habilitar si usas Jetpack Compose
    }
    // composeOptions { // Configuración de Compose si se usa
    //     kotlinCompilerExtensionVersion = "..." // Versión del compilador de Compose
    // }
    // packaging { // Opciones de empaquetado, si son necesarias
    //     resources {
    //         excludes += "/META-INF/{AL2.0,LGPL2.1}"
    //     }
    // }
}

dependencies {



    implementation ("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation ("com.airbnb.android:lottie:5.2.0")
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.android.gms:play-services-base:18.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation ("com.itextpdf:itext7-core:7.2.5")
    implementation ("com.itextpdf:layout:7.2.5")
    implementation("com.itextpdf:pdfa:7.2.5")
    implementation (libs.itext7.core)
    implementation (libs.layout)

    // === Core Android & UI ===
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)


    // implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // implementation(libs.androidx.lifecycle.livedata.ktx)
    // implementation(libs.androidx.navigation.fragment.ktx) // Si usas Fragments
    // implementation(libs.androidx.navigation.ui.ktx)

    // === Firebase ===
    // Importa la BoM para manejar versiones
    implementation(platform(libs.firebase.bom))
    // Dependencias específicas (sin versión, gestionada por BoM)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.play.services.auth) // Google Sign-In



    // === Testing ===
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}