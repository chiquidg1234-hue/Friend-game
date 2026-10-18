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

// Enlace para donar (PayPal, Ko-fi, Mercado Pago…). Ver Donations.kt.
val donateUrl = (System.getenv("FG_DONATE_URL") ?: "").trim().replace("\\", "").replace("\"", "")

// Correo para denuncias y consultas (ver Legal.kt). Google Play pide una forma de denunciar.
val contactEmail = (System.getenv("FG_CONTACT_EMAIL") ?: "").trim().replace("\\", "").replace("\"", "")

// Clave de subida para Google Play: va en secretos de GitHub, nunca en el repo (ver docs/PLAY_STORE.md).
val uploadKeystore = System.getenv("FG_UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.friendgame.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.friendgame.app"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "DONATE_URL", "\"$donateUrl\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"$contactEmail\"")
    }

    // Dos versiones de la app:
    // - github: la de siempre (APK en GitHub, se actualiza sola, se pasa por Quick Share, donación por enlace).
    // - play: para Google Play (sin actualización propia ni instalar APKs, sin pagos externos).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "STORE_BUILD", "false")
        }
        create("play") {
            dimension = "store"
            // Nombre de paquete propio: com.friendgame.app podría estar tomado en Play y no se
            // puede cambiar después de publicar.
            applicationId = "io.github.chiquidg1234.friendgame"
            buildConfigField("boolean", "STORE_BUILD", "true")
            buildConfigField("String", "DONATE_URL", "\"\"")
        }
    }

    sourceSets {
        // Términos y privacidad: los mismos textos que se publican en GitHub Pages.
        getByName("main").assets.srcDirs("src/main/assets", rootProject.file("docs/legal"))
    }

    buildFeatures {
        buildConfig = true
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
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("FG_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("FG_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("FG_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    productFlavors.getByName("github").signingConfig = signingConfigs.getByName("friendgame")
    // Sin clave de subida, la versión de Play sale sin firmar (Play no la acepta así).
    productFlavors.getByName("play").signingConfig =
        if (uploadKeystore != null) signingConfigs.getByName("upload") else null
    buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("friendgame")

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
