plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "feature.discovery"
    compileSdk = 35
    defaultConfig { minSdk = 24; targetSdk = 35 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(project(":core-ble"))
    implementation(project(":data"))
    implementation(platform("androidx.compose:compose-bom:2024.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.accompanist:accompanist-permissions:0.35.2-beta")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
