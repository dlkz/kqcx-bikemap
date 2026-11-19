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
require(appVersionParts.size == 3 && appVersionParts.all { part ->
    part.isNotEmpty() && part.all { character -> character in '0'..'9' }
}) {
    "versionName 必须使用三段数字格式：$appVersionName"
}
val appVersionMajor = requireNotNull(appVersionParts[0].toIntOrNull()) {
    "versionName 主版本超出整数范围：$appVersionName"
}
val appVersionMinor = requireNotNull(appVersionParts[1].toIntOrNull()) {
    "versionName 小版本超出整数范围：$appVersionName"
}
val appVersionPatch = requireNotNull(appVersionParts[2].toIntOrNull()) {
    "versionName 补丁号超出整数范围：$appVersionName"
}
val appVersionTrack = "$appVersionMajor.$appVersionMinor.$appVersionPatch"
require(appVersionTrack == appVersionName) {
    "versionName 不能包含前导零：$appVersionName"
}

/**
 * 构建号按完整“主版本.小版本.补丁号”重置：`buildNumberFor` 记录当前构建号属于哪个
 * 版本轨道，版本变化后从 1 重新开始。
 */
val recordedBuildNumberTrack = versionProperties.getProperty("buildNumberFor", "")
val storedBuildNumber = requireNotNull(
    versionProperties.getProperty("buildNumber", "1").toIntOrNull()
) {
    "buildNumber 必须是整数：${versionProperties.getProperty("buildNumber")}"
}
val appBuildNumber =
    if (recordedBuildNumberTrack == appVersionTrack) storedBuildNumber else 1

/**
 * versionCode = 主版本 × 1,000,000 + 小版本 × 10,000 + 补丁号 × 100 + 构建号。
 *
 * 版本变化时构建号从 1 重计，同时 versionCode 始终单调递增，覆盖安装不会被判成降级。
 * 每一位都留出足够进位空间；越界或顺序冲突时直接构建失败。
 */
fun encodeVersionCode(major: Int, minor: Int, patch: Int, build: Int): Int =
    major * 1_000_000 +
            minor * 10_000 +
            patch * 100 +
            build

require(appVersionMajor in 1..2146) {
    "versionName 主版本超出可编码范围（1..2146）：$appVersionName"
}
require(appVersionMinor in 0..99) {
    "versionName 小版本必须小于 100：$appVersionName"
}
require(appVersionPatch in 0..99) {
    "versionName 补丁号必须小于 100：$appVersionName"
}
require(appBuildNumber in 1..99) {
    "构建号必须在 1..99 之间：$appBuildNumber（versionName=$appVersionName）"
}
require(encodeVersionCode(2, 3, 99, 99) < encodeVersionCode(2, 4, 0, 1)) {
    "versionCode 在小版本边界必须单调递增"
}
require(encodeVersionCode(2, 99, 99, 99) < encodeVersionCode(3, 0, 0, 1)) {
    "versionCode 在主版本边界必须单调递增"
}

val appVersionCode = encodeVersionCode(
    appVersionMajor,
    appVersionMinor,
    appVersionPatch,
    appBuildNumber
)

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}
