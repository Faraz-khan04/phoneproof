import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Placeholder id. Pick the final name + package before the Worker and verify page pin it.
    namespace = "app.phoneproof"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.phoneproof"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"

        // Fill these once the services exist; empty = feature runs in offline mode.
        // Verify server (Cloudflare Worker), e.g. "https://verify.example.com"
        buildConfigField("String", "VERIFY_BASE_URL", "\"\"")
        // Google Cloud project NUMBER linked to Play Integrity in the Play Console (digits only).
        buildConfigField("long", "INTEGRITY_PROJECT_NUMBER", "0L")
        // AdMob: Google's official TEST ids. Replace with yours only in the release build.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        buildConfigField("String", "AD_REWARDED_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.play.integrity)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Google's hardware attestation roots (both the legacy RSA and the 2026 ECDSA P-384 root).
// Downloaded once into assets on the first build; delete the file to refresh after a rotation.
val fetchAttestationRoots = tasks.register("fetchAttestationRoots") {
    val out = layout.projectDirectory.file("src/main/assets/attestation_roots.json").asFile
    outputs.file(out)
    onlyIf { !out.exists() }
    doLast {
        out.parentFile.mkdirs()
        out.writeText(URI("https://android.googleapis.com/attestation/root").toURL().readText())
    }
}
tasks.named("preBuild") { dependsOn(fetchAttestationRoots) }
