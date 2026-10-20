import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * Signing details, kept out of the repository in `keystore.properties`
 * (see keystore.properties.example). Absent on a fresh checkout, in which case
 * the release build still runs and simply comes out unsigned rather than
 * failing — only whoever holds the key can produce a shippable APK.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/*
 * Telegram (TDLib) credentials and egress proxy, supplied locally and never
 * committed. A fresh checkout
 * without these lines still builds: apiId degrades to 0 (TelegramAuth treats
 * that as "not configured" and refuses to start) and the proxy fields degrade
 * to system-default networking.
 */
val tgApiId: Int = (
    localProps.getProperty("TG_API_ID")
        ?: System.getenv("TG_API_ID")
        ?: ""
    ).trim().toIntOrNull() ?: 0
val tgApiHash: String = (
    localProps.getProperty("TG_API_HASH")
        ?: System.getenv("TG_API_HASH")
        ?: ""
    ).trim()
val proxyHost: String = (
    localProps.getProperty("PROXY_HOST")
        ?: System.getenv("PROXY_HOST")
        ?: ""
    ).trim()
val proxyPort: Int = (
    localProps.getProperty("PROXY_PORT")
        ?: System.getenv("PROXY_PORT")
        ?: ""
    ).trim().toIntOrNull() ?: 0

/*
 * Bump this by hand before cutting each sideloaded test build ("beta2",
 * "beta3", ...) and blank it out before cutting the real release. It is
 * appended to the versionName below as a "-suffix", marking the build as a
 * pre-release rather than the clean release of the same number.
 *
 * Applied to release builds as well as debug ones, and that is the whole
 * point of it. A sideloaded beta is a *release* build — signed with the real
 * key, installed over the real package — so leaving the marker off it is
 * exactly the case that strands a tester: their build calls itself 1.6.1 and
 * the published 1.6.1 then matches it. Blanking this line is the one step
 * that turns a beta into a release, so it is the one place to get right.
 */
val betaSuffix = ""

android {
    namespace = "com.extrumz.app"
    // Compiled against 37; targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.extrumz.app"
        // 26 keeps reach wide; real-time blur (RenderEffect) kicks in on API 31+,
        // Haze falls back to a translucent scrim below that.
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Telegram API credentials + proxy (see local.properties, never committed).
        buildConfigField("int", "TG_API_ID", "$tgApiId")
        buildConfigField("String", "TG_API_HASH", "\"${tgApiHash.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PROXY_HOST", "\"${proxyHost.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("int", "PROXY_PORT", "$proxyPort")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // applicationId can only be overridden per flavor, not per build type, so a
    // dev/prod dimension exists purely to let both sit installed side by side
    // on the same device instead of the dev build overwriting the prod one.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationId = "com.dev.extrumz"
            resValue("string", "app_name", "Extrumz Dev")
        }
        create("prod") {
            dimension = "env"
            // Matches defaultConfig — this is the package already shipped/installed.
        }
    }

    signingConfigs {
        // Both halves have to be there, not just the properties file: it *names*
        // the keystore rather than containing it, and both are gitignored
        // separately, so a checkout can easily end up with the one and not the
        // other. A signing config pointing at a keystore that is not on disk
        // fails the release build outright at validateSigningRelease — which is
        // exactly the failure the unsigned fallback above exists to avoid, so
        // the keystore has to be looked for rather than assumed.
        val store = signing.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            create("release") {
                storeFile = store
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
        }
        release {
            // Carried here too — see [betaSuffix]. A sideloaded beta is a
            // release build, and it is the one that most needs the marker.
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
            /*
             * On for what it does to speed, not size. Compose is written to be
             * run through R8 — without it every composable keeps the debug-era
             * shape the compiler emits, and the whole UI runs measurably slower.
             *
             * Nothing is renamed (-dontobfuscate), and every library that reaches
             * for classes by name — ONNX's JNI, protobuf-lite, jsoup —
             * is kept whole: see proguard-rules.pro. What R8
             * is left to optimise is Compose, Media3, coroutines and our own
             * code, which is where the time goes. Checked on a device through the
             * `benchmark` build type below before it ships.
             */
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null without a keystore to sign with: the build then produces
            // app-release-unsigned.apk instead of failing outright.
            signingConfig = signingConfigs.findByName("release")
        }
        /*
         * The release build, installable next to the dev and prod apps: same R8,
         * same non-debuggable runtime, signed with the debug key under its own
         * package so it never replaces either. For measuring startup the way
         * users get it and for checking that shrinking broke nothing — a debug
         * build is interpreted and verified at runtime and says little about
         * either. `./gradlew installDevBenchmark`.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // jspecify ships this descriptor.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Unit tests run against a stub android.jar whose methods throw
            // rather than return. That is the right default for anything whose
            // behaviour depends on the framework, and wrong for android.util.Log
            // — which [TrackLog] calls on every decision the source layer makes,
            // so a test of that layer fails on the logging rather than on the
            // logic it was written to check.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- Compose (Material 3) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Pinned above the BOM's 1.7.6: [IosOverscroll] uses OverscrollFactory,
    // which that version doesn't have. Newer foundation alongside the BOM's
    // older ui/material3 is a combination Compose supports deliberately —
    // foundation depends on ui, not the reverse — and this exact pairing was
    // already in effect (foundation was reaching 1.10.0 transitively through
    // the liquid-glass library before that dependency was removed).
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media playback: Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // Audio is progressive, but Apple serves its motion artwork as HLS — this
    // is what lets the animated sleeve play it. See CanvasArtworkPlayer.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // Substitute sources hand back manifests rather than files, and which kind is
    // the backend's choice, not ours: the Tidal one served `.m3u8` until
    // September 2026 and `.mpd` after it, for the same track and the same
    // request. Without this artifact a DASH manifest is not merely unplayed —
    // DefaultMediaSourceFactory cannot build a source for it, falls back to
    // progressive, and the extractors try to sniff XML as audio
    // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED). See withResolvedStreamType.
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // ---- Images: Coil 3 + Palette (dominant colors for the mesh gradient) ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ---- Frosted glass / progressive blur (Telegram-style bars) ----
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // ---- QR encoding, for the party invite ----
    // `core` only: the `android-core`/`zxing-android-embedded` artifacts bring
    // a camera scanner and an Activity with it, and nothing here reads a code —
    // a party is joined by tapping somebody else's link or typing six
    // characters. This produces the bit matrix; the drawing is ours, in
    // [com.extrumz.app.ui.components.QrCode], so the result is styled like
    // the rest of the app rather than a stock black-and-white bitmap.
    implementation("com.google.zxing:core:3.5.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- HTML parsing ----
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // ---- Auth/session storage ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ---- Automix: on-device beat/downbeat model (Beat This!, MIT-licensed) ----
    // The full android artifact, not onnxruntime-mobile: mobile only loads .ort
    // files, which would put an offline conversion step between the model and
    // the app for a saving that does not matter in a self-distributed APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    // ---- Telegram: TDLib client (JitPack — same artifact proven in the old project) ----
    implementation("com.github.tdlibx:td:1.8.56")

    // ---- Telegram library index: Room + bundled SQLite (FTS5 needs the bundled
    // engine on every API level — the framework SQLite only gained FTS5 in
    // Android 11 while minSdk is 26). Versions match the old project, where the
    // same schema and Fts5Callbacks ran green. ----
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

/*
 * A debug APK lands on the device uncompiled — `dumpsys package dexopt` reports
 * it as run-from-apk — so every launch verifies the whole app's classes at
 * runtime before a line of our code runs. Measured on the BlueStacks box, that
 * was about two seconds of every cold start and most of why the dev build felt
 * so much slower than a release one. `verify` is the cheapest filter that
 * removes it (~15s once per install), and unlike `speed` it leaves the debug
 * build debuggable exactly as before.
 *
 * Runs after `installDevDebug` from the command line. Android Studio's Run
 * button deploys on its own and never reaches this task, so from there run
 * `./gradlew verifyDevInstall` after installing.
 */
val verifyDevInstall = tasks.register("verifyDevInstall") {
    group = "install"
    description = "Pre-verifies the installed dev build on every connected device."
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val adbPath = adb.get().asFile.absolutePath
        val serials = ProcessBuilder(adbPath, "devices").start()
            .inputStream.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
        serials.forEach { serial ->
            logger.lifecycle("verifyDevInstall: compiling com.dev.extrumz on $serial")
            ProcessBuilder(
                adbPath, "-s", serial, "shell", "cmd", "package", "compile",
                "-m", "verify", "-f", "com.dev.extrumz",
            ).inheritIO().start().waitFor()
        }
    }
}
tasks.matching { it.name == "installDevDebug" }.configureEach { finalizedBy(verifyDevInstall) }
