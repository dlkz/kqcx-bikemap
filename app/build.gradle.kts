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
val appVersionParts = appVersionName.split('.')
val appVersionMajor = appVersionParts.getOrNull(0)?.toIntOrNull() ?: 2
val appVersionMinor = appVersionParts.getOrNull(1)?.toIntOrNull() ?: 0
val appVersionTrack = "$appVersionMajor.$appVersionMinor"

/**
 * 构建号按“主版本.小版本”重置：`buildNumberFor` 记录当前构建号属于哪个版本轨道，
 * 同一小版本下的补丁版本继续沿用构建序列。
 */
val recordedBuildNumberTrack = versionProperties.getProperty("buildNumberFor", "")
val storedBuildNumber = versionProperties.getProperty("buildNumber", "1").toInt()
val appBuildNumber =
    if (recordedBuildNumberTrack == appVersionTrack) storedBuildNumber else 1

/**
 * versionCode = 主版本 × 100,000 + 小版本 × 1,000 + 构建号。
 *
 * 构建号可以按小版本归零，同时 versionCode 始终单调递增，覆盖安装不会被判成降级。
 * 约束：构建号 1..999、小版本 0..99；越界会串号，这里直接构建失败。
 */
val appVersionCode =
    appVersionMajor * 100_000 + appVersionMinor * 1_000 + appBuildNumber

require(appVersionMajor in 1..21000) {
    "versionName 主版本超出可编码范围（1..21000）：$appVersionName"
}
require(appVersionMinor in 0..99) {
    "versionName 小版本必须小于 100：$appVersionName"
}
require(appBuildNumber in 1..999) {
    "构建号必须在 1..999 之间：$appBuildNumber（versionName=$appVersionName）"
}

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
        versionCode = appVersionCode
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
    properties.setProperty("buildNumberFor", appVersionTrack)
    properties.setProperty("buildNumber", (appBuildNumber + 1).toString())
    versionPropertiesFile.writeText(
        "versionName=${properties.getProperty("versionName")}\n" +
                "buildNumberFor=${properties.getProperty("buildNumberFor")}\n" +
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
