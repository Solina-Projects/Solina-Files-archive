plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.solina.fm"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.solina.fm"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { aidl = true }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/NOTICE.md", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("com.github.lookfirst:sardine:5.12")
}
