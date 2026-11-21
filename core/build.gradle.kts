import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin JVM module: report model, canonical JSON, DER/attestation parser, verifier.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
