@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2048m"
    dependsOn("buildRustHost")
    // Native implementation changes must invalidate JVM test results too.
    inputs.file(rootProject.file("rust/vialen-core/target/release/libvialen_core.dylib"))
        .withPropertyName("rustHostLibrary")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "java.library.path",
        "${rootProject.file("rust/vialen-core/target/release")}:${rootProject.file("rust/vialen-core/target/debug")}"
    )
    doFirst {
        listOf(
            "ossDebug", "ossRelease",
            "fdroidDebug", "fdroidRelease",
            "playDebug", "playRelease",
            "previewDebug", "previewRelease"
        ).forEach { variant ->
            copy {
                from("$projectDir/schemas")
                into("${project.buildDir}/intermediates/assets/$variant/merge${variant.replaceFirstChar { it.uppercase() }}Assets")
            }
            copy {
                from("$projectDir/schemas")
                into("${project.buildDir}/intermediates/assets/test/$variant/merge${variant.replaceFirstChar { it.uppercase() }}TestAssets")
            }
            copy {
                from("$projectDir/schemas")
                into("${project.buildDir}/intermediates/javaResources/test${variant.replaceFirstChar { it.uppercase() }}UnitTest")
            }
        }
    }
}

val buildRustHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libvialen_core.dylib for the macOS host JVM (used by unit tests via java.library.path)"
    val rustRoot = rootProject.file("rust/vialen-core")
    val buildScript = rootProject.file("scripts/build-rust-host.sh")
    inputs.file(rootProject.file("rust-toolchain.toml"))
    inputs.file(rustRoot.resolve("Cargo.toml"))
    inputs.file(rustRoot.resolve("Cargo.lock"))
    inputs.dir(rustRoot.resolve("src"))
    inputs.file(buildScript)
    outputs.file(rustRoot.resolve("target/release/libvialen_core.dylib"))
    commandLine("bash", buildScript.absolutePath)
}


val rustJniLibsDir = layout.buildDirectory.dir("generated/rustJniLibs")

android.sourceSets.getByName("main").jniLibs.srcDir(rustJniLibsDir)

val buildRustAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Phase E1 Rust JNI POC for all supported Android ABIs"
    val rustRoot = rootProject.file("rust/vialen-core")
    val buildScript = rootProject.file("scripts/build-rust-android.sh")
    inputs.file(rootProject.file("rust-toolchain.toml"))
    inputs.file(rustRoot.resolve("Cargo.toml"))
    inputs.file(rustRoot.resolve("Cargo.lock"))
    inputs.dir(rustRoot.resolve("src"))
    inputs.file(buildScript)
    outputs.dir(rustJniLibsDir)
    commandLine("bash", buildScript.absolutePath, rustJniLibsDir.get().asFile.absolutePath)
}

tasks.configureEach {
    if (name.startsWith("merge") &&
        (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))
    ) {
        dependsOn(buildRustAndroid)
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.11.1")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
