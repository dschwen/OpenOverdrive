plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.openoverdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.openoverdrive"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Installable by tapping (not testOnly), but still uses debug keystore
        create("internal") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            // Use debug variants of library modules when building 'internal'
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-ble"))
    implementation(project(":core-protocol"))
    implementation(project(":data"))
    implementation(project(":feature-discovery"))
    implementation(project(":feature-drive"))

    implementation(platform("androidx.compose:compose-bom:2024.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.accompanist:accompanist-permissions:0.35.2-beta")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
