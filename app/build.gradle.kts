plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cleardns.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cleardns.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.0"

        // AdMob App ID. Placeholder = Google's test app ID.
        // TODO: replace with the real ID from your AdMob console (App settings) before publishing.
        resValue("string", "admob_app_id", "ca-app-pub-3940256099942544~3347511713")
        // Ad unit IDs (placeholders = Google test units).
        buildConfigField("String", "AD_UNIT_BANNER", "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "AD_UNIT_REWARDED", "\"ca-app-pub-3940256099942544/5224354917\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.play.services.ads)

    // Firestack: motor de VPN/DNS/firewall de RethinkDNS (gomobile AAR precompilado).
    implementation("com.github.celzero:firestack:28834df5cd@aar")
}
