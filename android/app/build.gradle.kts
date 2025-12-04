plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.acktarius.hnsgo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acktarius.hnsgo"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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