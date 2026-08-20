import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 签名信息从 `local.properties` 读取，**不进版本库**（该文件已在 .gitignore 中）。
 *
 * 需要的四个键见 README「构建与运行 · 打签名包」。任一缺失时，release 构建
 * 退化为产出未签名包（`app-release-unsigned.apk`），构建本身不会失败——
 * 这样克隆本仓库的人无需持有密钥也能完整构建。
 */
val signingProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun signingProp(key: String): String? = signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val hasSigningConfig =
    listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        .all { signingProp(it) != null }

android {
    namespace = "tech.illusion.todayinhistory"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.illusion.todayinhistory"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(signingProp("RELEASE_STORE_FILE")!!)
                storePassword = signingProp("RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("RELEASE_KEY_ALIAS")
                keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 缺少签名配置时留空，产出未签名包而不是让构建失败。
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release") else null
            // 保持关闭：Spatial SDK 依赖反射与 ECS 注册，开启混淆需额外的 keep 规则
            // 并重新做一轮设备验证，不宜与其他改动一起动。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidResources {
        noCompress += listOf(".bundle")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.core)
    implementation(libs.platform)
    implementation(libs.foundation)
    implementation(libs.design)
    implementation(libs.sense)
    implementation(libs.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(project(":editor-asset"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}

// RealAssetIntegrityTest 通过 java.io.File 直接读 src/main/assets，绕过了 Gradle 的
// 输入追踪。不声明这个输入的话，只改资产而不动代码时 Gradle 会判定单测任务 UP-TO-DATE
// 直接跳过——而那恰恰是这条测试唯一该起作用的场景（Task 1 重新生成资产之后）。
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/assets"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("historyAssets")
}