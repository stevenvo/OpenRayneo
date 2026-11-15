plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openrayneo.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.openrayneo.android.english"
        minSdk = 33
        targetSdk = 36
        versionCode = 26
        versionName = "1.0.0-en7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = "release"
    buildTypes {
        getByName("release") {
            // v1.0 retains the existing development certificate for in-place upgrades.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val verifySpeechModel by tasks.registering {
    doLast {
        check(file("src/main/assets/vosk-model-small-en-us-0.15/am/final.mdl").isFile) {
            "Run python3 scripts/prepare-speech-model.py from the repository root before building."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySpeechModel) }
