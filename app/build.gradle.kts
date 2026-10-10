import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub Actions cada compilación sube el número de versión, así el APK nuevo
// se instala encima del anterior sin perder tus amigos ni tus audios. La etiqueta de la
// versión publicada (v1.0.N) lleva ese mismo número: la app la usa para saber si hay una nueva.
// FG_VERSION_CODE permite fijarlo a mano (la prueba en emulador lo usa).
val buildNumber = (System.getenv("FG_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.friendgame.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.friendgame.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Clave de firma de esta app de juego. Va en el repo a propósito para que
        // cualquier compilación (local o en GitHub) firme igual y las
        // actualizaciones se instalen encima. Si algún día la publicas en
        // Play Store, crea una clave nueva y guárdala fuera del repo.
        create("friendgame") {
            storeFile = rootProject.file("signing/friendgame.jks")
            storePassword = "friendgame"
            keyAlias = "friendgame"
            keyPassword = "friendgame"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("friendgame")
        }
        debug {
            signingConfig = signingConfigs.getByName("friendgame")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress.add("ogg")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Generar y leer los códigos QR de invitación a grupos (sin servicios de Google).
    implementation("com.google.zxing:core:3.5.3")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
