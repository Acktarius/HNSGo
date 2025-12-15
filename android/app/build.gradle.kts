plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    kotlin("kapt")
}

// Helper function to read .env file
fun readEnv(key: String, default: String = ""): String {
    val envFile = file("${project.rootDir}/../.env")
    if (!envFile.exists()) {
        return default
    }
    return envFile.readLines()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?: default
}

// Helper function to read signing secrets (from env vars or .envsecret file)
fun readEnvSecret(key: String, default: String = ""): String {
    // First check environment variables (for CI/GitHub Actions)
    val envValue = System.getenv(key)
    if (envValue != null && envValue.isNotEmpty()) {
        return envValue
    }
    
    // Fall back to .envsecret file (for local development)
    val envSecretFile = file("${project.rootDir}/../.envsecret")
    if (!envSecretFile.exists()) {
        return default
    }
    return envSecretFile.readLines()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?: default
}

android {
    namespace = "com.acktarius.hnsgo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acktarius.hnsgo"
        minSdk = 28
        targetSdk = 34
        versionCode = readEnv("ANDROID_VERSION_CODE", "1").toInt()
        versionName = readEnv("APP_VERSION", "1.0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // Signing configuration from .envsecret
    val keystorePath = readEnvSecret("ANDROID_KEYSTORE_PATH", "")
    val keystorePassword = readEnvSecret("ANDROID_KEYSTORE_PASSWORD", "")
    val keyAliasValue = readEnvSecret("ANDROID_KEY_ALIAS", "")
    val keyPasswordValue = readEnvSecret("ANDROID_KEY_PASSWORD", "")

    if (keystorePath.isNotEmpty() && keystorePassword.isNotEmpty() && keyAliasValue.isNotEmpty()) {
        // Resolve keystore path (relative to android/app directory)
        val keystoreFile = file(keystorePath)
        
        if (keystoreFile.exists()) {
            signingConfigs {
                create("release") {
                    storeFile = keystoreFile
                    storePassword = keystorePassword
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
            
            buildTypes {
                getByName("release") {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
    }
    
    // Customize APK output filename for release builds only
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                val versionName = variant.versionName
                output.outputFileName = "HNSGo-v${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dnsjava:dnsjava:3.6.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.upokecenter:cbor:4.5.1")
    
    // Room database for favorites and history
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // Lifecycle for ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
}

detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    allRules = false // activate all available (even unstable) rules
    config.setFrom("${project.rootDir}/detekt.yml")
    baseline = file("${project.projectDir}/detekt-baseline.xml") // Ignore existing issues
    
    parallel = true // parallel compilation of files
    
    reports {
        html {
            required.set(true)
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.html"))
        }
        xml {
            required.set(false)
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.xml"))
        }
        txt {
            required.set(false)
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.txt"))
        }
        sarif {
            required.set(true) // SARIF format for GitHub integration
            outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.sarif"))
        }
    }
}