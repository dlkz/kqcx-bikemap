import java.util.Properties

plugins {
    id("com.android.application")
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    if (versionPropertiesFile.exists()) {
        versionPropertiesFile.inputStream().use { load(it) }
    }
}
val appVersionName = versionProperties.getProperty("versionName", "2.0.0")
val appBuildNumber = versionProperties.getProperty("buildNumber", "4").toInt()

val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val prepareLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) {
        into("legal")
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("legal")
    }
    from(rootProject.file("LICENSES")) {
        into("legal/LICENSES")
    }
    into(legalAssetsDir)
}

android {
    namespace = "com.kqcx.bikemap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kqcx.bikemap"
        minSdk = 26
        targetSdk = 36
        versionCode = appBuildNumber
        versionName = appVersionName
        buildConfigField("int", "BUILD_NUMBER", appBuildNumber.toString())
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeType = keystoreProperties.getProperty("storeType", "PKCS12")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 仅供本地留存的未压缩正式版：包名/签名与 release 相同，不用于发布。
        create("releasePlain") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main").assets.srcDir(legalAssetsDir)
    }
}

var buildNumberRecorded = false

fun recordNextBuildNumber() {
    if (buildNumberRecorded || !versionPropertiesFile.exists()) {
        return
    }
    val properties = Properties().apply {
        versionPropertiesFile.inputStream().use { load(it) }
    }
    properties.setProperty("versionName", appVersionName)
    properties.setProperty("buildNumber", (appBuildNumber + 1).toString())
    versionPropertiesFile.writeText(
        "versionName=${properties.getProperty("versionName")}\n" +
                "buildNumber=${properties.getProperty("buildNumber")}\n"
    )
    buildNumberRecorded = true
}

tasks.matching {
    it.name in setOf(
        "assembleDebug",
        "assembleRelease",
        "bundleDebug",
        "bundleRelease"
    )
}.configureEach {
    doLast {
        recordNextBuildNumber()
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.zxing:core:3.4.1")
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}
