import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing key lives in keys/ (never printed, never committed). The same key must be used for
// every build, otherwise an update will not install over the previous version in the car.
val signingProps = Properties().apply {
    val f = rootProject.file("keys/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Version lives in version.properties; the store publish script raises it for every new release,
// so the car sees the new build as an update.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.zmd.carhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zmd.carhub"
        minSdk = 26
        // 33 (Android 13), not higher: BYD DiLink's USB installer ("Third Party Apps <code>" folder)
        // is documented to need targetSdk ≤ 33, minSdk ≤ 29 and ARM64 (2026-11-18). Play Protect
        // only warns about targets more than 2 below the screen's Android version, and the old
        // install blocks were caused by the accessibility service, not by the target. Keeps the
        // Bluetooth / notification runtime prompts (Perms + MainActivity); the specialUse
        // foreground-service declarations in the manifest are harmless at 33.
        targetSdk = 33
        // 64-bit ARM for newer car screens, 32-bit ARM (armeabi-v7a) for older BYD DiLink screens
        // (without it they refuse the APK as "not compatible"). x86_64 only for the PC emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        versionCode = (versionProps.getProperty("code") ?: "1").toInt()
        versionName = versionProps.getProperty("name") ?: "0.1"
    }

    signingConfigs {
        create("hub") {
            storeFile = rootProject.file("keys/zmdcar.jks")
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
            // New key since 3.9 (2026-11-18): Play Protect blocked installs signed with the old
            // carhub.jks (kept in keys/old-carhub-key). v1 too: some car installers only check JAR.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hub")
        }
        debug {
            signingConfig = signingConfigs.getByName("hub")
        }
        // Everything (accessibility helper + home-screen option, from src/full/AndroidManifest.xml).
        // Install it from the PC over adb only: Play Protect blocks hand installs of any APK that
        // declares an accessibility service. `release` is the build people install by hand.
        create("full") {
            initWith(getByName("release"))
            versionNameSuffix = "-full"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // The bundled DiPlay / DiAuto APKs are read with openFd(), which needs them stored uncompressed.
    androidResources {
        noCompress += "apk"
    }

    // GraphHopper's jars carry duplicate license/notice files; keep none of them in the APK.
    packaging {
        // MapLibre and the Android Auto engine may both carry the C++ runtime.
        jniLibs { pickFirsts += listOf("**/libc++_shared.so") }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/9/module-info.class", "module-info.class")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    // Built-in map: MapLibre vector map with OpenFreeMap styles (free, no key, no Google services).
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    // Map tiles/fonts go through one OkHttp interceptor (MapTiles.kt). Same version MapLibre ships.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Lets the side bar open other apps as resizable windows beside it (ActivityOptions windowing mode).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Offline routing on the car (Iraq graph built on the PC with the same GraphHopper version).
    implementation("com.graphhopper:graphhopper-core:1.0") {
        exclude(group = "org.apache.xmlgraphics")   // desktop-only (image export), not needed on the car
    }
    // Android Auto, built in: the ZMD Auto engine (A:\byd\ZMD-Auto :engine, AGPL-3.0). Its screens
    // and services run in our ":auto" process and report to HubService (ZmdAuto.kt). Shipping it
    // means ZMD Car's source must be offered to whoever gets the app.
    implementation("com.zmd:auto-engine:0.3.12-zmd.2")
}
