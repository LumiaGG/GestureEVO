@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "io.github.lumiagg.gestureevo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lumiagg.gestureevo"
        minSdk = 24
        targetSdk = 36
        versionCode = 261125001
        versionName = "2.2-1125001"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    val signingEnvironment = listOf(
        "GESTUREEVO_KEYSTORE_FILE", "GESTUREEVO_KEYSTORE_PASSWORD",
        "GESTUREEVO_KEY_ALIAS", "GESTUREEVO_KEY_PASSWORD",
    ).map { providers.environmentVariable(it).orNull }
    val releaseSigning = if (signingEnvironment.all { !it.isNullOrEmpty() }) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(signingEnvironment[0]))
            storePassword = signingEnvironment[1]
            keyAlias = signingEnvironment[2]
            keyPassword = signingEnvironment[3]
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true  // 启用 BuildConfig
        viewBinding = true
        aidl = true
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("schemas")
        }
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
            assets.srcDir(layout.buildDirectory.dir("generated/projectDocuments"))
        }
    }
    packaging {
        // Store the larger AAR libraries compressed in the downloadable APK.
        jniLibs.useLegacyPackaging = true
    }
}

val prepareProjectDocuments by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("generated/projectDocuments"))
    from(rootProject.file("LICENSE")) {
        into("licenses")
        rename { "project_license.txt" }
    }
    from(layout.projectDirectory.file("src/main/java/io/github/lumiagg/gestureevo/drop/MMGG-NOTICE")) {
        into("licenses")
        rename { "MMGG-NOTICE.txt" }
    }
}

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(prepareProjectDocuments)
}

configurations.configureEach {
    resolutionStrategy.force(
        libs.kotlinx.serialization.core.get().toString(),
        libs.kotlinx.serialization.json.get().toString()
    )
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Android UI and platform integrations.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    implementation(libs.brv)
    implementation(libs.appiconloader)

    // Persistence and JSON; processors share the runtime versions.
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Privileged platform access.
    implementation(libs.bundles.shizuku)
    implementation(libs.hiddenapibypass)

    // Text processing, offline OCR, barcode decoding and WebDAV.
    // Local binary provenance and checksum: third_party/binary-manifest.json.
    implementation(files("libs/tinypinyin-2.0.3.jar"))
    implementation(libs.ahocorasick)
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
    implementation(libs.zxing.core)
    implementation(libs.sardine.android)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
