import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The ntfy topic the "report a problem" feature publishes to. It is read from
// local.properties, which is machine-local and git-ignored, so the topic never enters
// the repository — a topic hard-coded here would let anyone who reads the source read
// the reports and publish fake notifications to the maintainer.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.hidgamepad"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hidgamepad"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        buildConfigField(
            "String",
            "NTFY_TOPIC",
            "\"${localProperties.getProperty("ntfy.topic", "").replace("\"", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
}
