// Top-level build file where you can add configuration options common to all sub-projects/modules.
// No aplicar plugins aquí si se usa la sintaxis moderna con Version Catalogs.
// Las definiciones de plugins ahora van en settings.gradle.kts o directamente en los módulos.
// Este archivo puede quedar casi vacío o usarse para configuraciones globales muy específicas.

// buildscript { // Sintaxis antigua, no necesaria con Version Catalogs
//     repositories {
//         google()
//         mavenCentral()
//     }
//     dependencies {
//         classpath("com.google.gms:google-services:...") // Forma antigua
//     }
// }

// allprojects { // Sintaxis antigua
//     repositories {
//         google()
//         mavenCentral()
//     }
// }

task("clean", Delete::class) {
    delete(rootProject.buildDir)
}