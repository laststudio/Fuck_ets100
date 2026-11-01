import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.shuaiqiu.fuckets100"
    compileSdk = 37

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    // CI 可覆盖签名轮换后的证书；本地构建也绑定生产证书，不能因未注入而跳过校验。
    val officialSignatureSha256 = "95fdebb3682f7c529584cfe11f27b73557426d211d53688beaa18434fdd84cfe"
    val expectedSignatureSha256 = System.getenv("EXPECTED_SIGNATURE_SHA256")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: officialSignatureSha256
    require(expectedSignatureSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
        "EXPECTED_SIGNATURE_SHA256 必须是 64 位十六进制签名证书摘要"
    }
    val hasReleaseSigningConfig = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.shuaiqiu.fuckets100"
        minSdk = 26
        targetSdk = 37
        versionCode = 26
        versionName = "2.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "EXPECTED_SIGNATURE_SHA256", "\"$expectedSignatureSha256\"")
        buildConfigField("String", "EXPECTED_PACKAGE_NAME", "\"$applicationId\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningConfig) {
                // CI injects release signing credentials through environment variables.
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            excludes += "META-INF/*.version"
            // kotlinx.coroutines debug infrastructure is useless at runtime.
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        // Remote update checks read version information from BuildConfig.
        buildConfig = true
    }
}

configurations.all {
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose")
    exclude(group = "org.jetbrains.androidx.navigationevent", module = "navigationevent-compose")
}

baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":core:platform"))
    implementation(project(":core:navigation"))
    implementation(project(":data:content"))
    implementation(project(":data:account"))
    implementation(project(":data:ets100"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:activation"))
    implementation(project(":feature:read"))
    implementation(project(":feature:account"))
    implementation(project(":feature:shared"))
    implementation(project(":feature:onboarding"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.miuix.navigation3)
    implementation(libs.androidx.navigationevent)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material.kolor)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    // Shizuku support allows file access without root where available.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // ZIP extraction, including password-protected archives.
    implementation(libs.zip4j)

    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
