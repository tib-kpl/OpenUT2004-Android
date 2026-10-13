plugins {
    id("com.android.application")
}

// Embarque dans l'APK (assets/runtime) : le runtime Linux arm64 (runtime/out/rootfs.tgz,
// .tgz car AGP decompresse les assets .gz). Ni le jeu ni le moteur ne sont embarques : l'appli
// telecharge le patch OldUnreal (moteur arm64) et, si l'utilisateur le demande, le jeu.
abstract class SyncRuntimeAssets : DefaultTask() {
    @get:InputFile abstract val rootfs: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        fs.sync {
            from(rootfs) { into("runtime") }
            into(outputDir)
        }
    }
}

val syncRuntimeAssets = tasks.register<SyncRuntimeAssets>("syncRuntimeAssets") {
    rootfs.set(rootProject.layout.projectDirectory.file("runtime/out/rootfs.tgz"))
}

android {
    namespace = "fr.tibkpl.openut2004"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.tibkpl.openut2004"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("OPENUT2004_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("OPENUT2004_VERSION_NAME") ?: "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Signature de l'APK release : cle fournie par variables d'environnement (secrets GitHub,
    // voir README) ; a defaut, cle de debug (APK installable mais non mis a jour d'une
    // version a l'autre si la cle change).
    val keystore = System.getenv("OPENUT2004_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("OPENUT2004_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENUT2004_KEY_ALIAS")
                keyPassword = System.getenv("OPENUT2004_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // proot et son loader doivent exister en fichiers executables (nativeLibraryDir)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += "tgz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Lecture du patch OldUnreal pour Linux (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.27.1")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncRuntimeAssets, SyncRuntimeAssets::outputDir)
    }
}
