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

// It is validated here as well as at runtime: a typo must fail the build with a clear
// message rather than producing an app whose only way out is a field the user cannot see.
val ntfyTopic = localProperties.getProperty("ntfy.topic", "").trim()
if (ntfyTopic.isNotEmpty() && !Regex("^[A-Za-z0-9_-]{1,64}$").matches(ntfyTopic)) {
    throw GradleException(
        "local.properties: ntfy.topic must match [A-Za-z0-9_-]{1,64} (letters, digits, " +
            "hyphen and underscore). Got \"$ntfyTopic\".",
    )
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
            "\"$ntfyTopic\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        // Side-loadable showcase build for the portfolio page: installs straight from a
        // browser, and deliberately ships without the maintainer's ntfy topic, so anyone
        // who downloads it cannot reach (or flood) the report topic. Signed with the debug
        // keystore, which is what regular debug installs use — this is not a release key.
        create("portfolio") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "NTFY_TOPIC", "\"\"")
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
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
