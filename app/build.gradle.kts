import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 正式签名放在仓库外（~/.pocketagent-release/keystore.properties），
// 避免把私钥和口令提交进 git。发布构建缺少正式签名时必须失败。
val releaseKeyProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.pocketagent-release/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.pocketagent.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pocketagent.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 105
        versionName = "1.0.0"
        // 分享版：不预置公司跳板机/主机，其余功能一致（打包时 -PshareBuild=true）
        buildConfigField("boolean", "SHARE_BUILD", (project.findProperty("shareBuild") ?: "false").toString())
        // 发布版默认不注入任何内网主机/中转站；只有显式 -PinternalBuild=true 才启用
        buildConfigField("boolean", "INTERNAL_BUILD", (project.findProperty("internalBuild") ?: "false").toString())
        // 中转站域名（可选）：发布版留空，内部构建用 -PrelayBaseUrl=https://... 注入
        buildConfigField("String", "RELAY_BASE_URL", "\"${project.findProperty("relayBaseUrl") ?: ""}\"")
        // 运营主体信息：上架前用 -PoperatorName=... -PcontactEmail=... 注入，默认留空由 App 显示「待填写」
        buildConfigField("String", "OPERATOR_NAME", "\"${project.findProperty("operatorName") ?: ""}\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"${project.findProperty("contactEmail") ?: ""}\"")
        // ICP 备案号（网站）与 App 备案号，商店审核和《App备案》要求展示
        buildConfigField("String", "ICP_NUMBER", "\"${project.findProperty("icpNumber") ?: ""}\"")
        buildConfigField("String", "APP_BEIAN", "\"${project.findProperty("appBeian") ?: ""}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseKeyProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseKeyProps.getProperty("storeFile"))
                storePassword = releaseKeyProps.getProperty("storePassword")
                keyAlias = releaseKeyProps.getProperty("keyAlias")
                keyPassword = releaseKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeyProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // OSGi multi-release descriptors have no role on Android.
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
            merges += "META-INF/LICENSE.md"
            merges += "META-INF/NOTICE.md"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mwiede:jsch:0.2.20")
    // 远程执行：SSH 到 remote-host / Mac，PEM 私钥在内存里加载（见 remote/SshKeyLoader.kt）
    implementation("com.hierynomus:sshj:0.41.2")
    // 本地读取 PDF 文本；扫描版 PDF 另外走 PdfRenderer 转图片
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // 项目已用 bcprov/bcpkix/bcutil-jdk18on 1.86；排除 PDFBox 自带的 1.72 整套旧 BC，避免重复类
        exclude(group = "org.bouncycastle")
    }
    // PDFBox 加密文档与 sshj 都依赖 BC；三件套显式锁到同一版本，不能只靠 sshj 的 runtime 依赖
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Debug builds and tests remain available without a release key.
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    doFirst {
        require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
            !releaseKeyProps.getProperty(it).isNullOrBlank()
        }) { "Release requires a private signing configuration outside the repository; use assembleDebug for local tests." }
    }
}
