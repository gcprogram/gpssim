plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gcprogram.gpssim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gcprogram.gpssim"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.7.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // osmdroid für Offline/Online OSM-Karten (wie in GCToolkit-Android)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Bridge, die Mapsforge-.map-Dateien als osmdroid-Tile-Source rendert (Offline-Karten,
    // z.B. von openandromaps.de oder download.mapsforge.org). Bringt die passende
    // Mapsforge-Version transitiv mit.
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")
    // Explizit nötig für org.mapsforge.map.reader.MapDatabase (Bounding-Box-Ermittlung beim
    // Umschalten auf Offline-Karten in MapScreen.kt) - wird von osmdroid-mapsforge NICHT
    // transitiv auf den Compile-Classpath des App-Moduls exportiert.
    implementation("org.mapsforge:mapsforge-map-reader:0.18.0")
    implementation("org.mapsforge:mapsforge-core:0.18.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Rendert die cache_type-SVGs (aus GCToolkit-Android übernommen) als Kartenmarker.
    // WICHTIG: die "-aar"-Variante NICHT verwenden - osmdroid-mapsforge/mapsforge-map bringt
    // bereits com.caverock:androidsvg transitiv mit; beide zusammen ergeben eine Duplicate-
    // Class-Kollision (com.caverock.androidsvg.BuildConfig) beim Build. Gleiche Koordinate wie
    // in GCToolkit-Android verwenden, dann löst Gradle nur eine Version auf.
    implementation("com.caverock:androidsvg:1.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
