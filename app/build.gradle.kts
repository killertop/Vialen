@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    ndkVersion = "28.1.13356709"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
        // Kotlin DAO implementations preserve suspend generics with KSP2.
        arg("room.generateKotlin", "true")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        resValues = true
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
        localeFilters += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW")
    }
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2048m"
    dependsOn("buildGoHost")
    inputs.file(rootProject.file("core/build/core-host"))
        .withPropertyName("goHostExecutable")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("vialen.core.host", rootProject.file("core/build/core-host").absolutePath)
    systemProperty("vialen.core.testBackend", "io.nekohasekai.sagernet.core.HostCoreBackend")
}

val buildGoHost = tasks.register<Exec>("buildGoHost") {
    group = "build"
    description = "Build the pure Go executable used by JVM contract tests"
    val coreRoot = rootProject.file("core")
    val script = rootProject.file("scripts/build-go-host.sh")
    inputs.files(fileTree(coreRoot) { include("**/*.go", "go.mod", "go.sum") })
    inputs.file(rootProject.file("scripts/go-toolchain.sh"))
    inputs.file(script)
    outputs.file(coreRoot.resolve("build/core-host"))
    commandLine("bash", script.absolutePath)
}

dependencies {

    implementation(fileTree("libs"))

    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.recyclerview)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.browser)
    implementation(libs.swiperefreshlayout)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.appcompat)
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)
    testImplementation("androidx.work:work-testing:${libs.versions.work.get()}")

    implementation(libs.material)
    implementation(libs.gson)

    implementation(libs.zxing.lite)
    implementation(libs.editorkit)
    implementation(libs.language.base)
    implementation(libs.language.json)

    implementation(libs.okhttp)
    implementation(libs.process.phoenix)
    implementation(libs.kryo)
    implementation(libs.guava)

    implementation(libs.recyclerview.fastscroll) {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.core)
    testImplementation(libs.test.ext.junit)
    testImplementation(libs.robolectric)

    constraints {
        // Robolectric and MockK inspect JDK classes in the Java 25 test process.
        // Keep these instrumentation libraries out of the Android runtime.
        for (module in listOf("asm", "asm-commons", "asm-tree")) {
            testImplementation("org.ow2.asm:$module:${libs.versions.asm.get()}") {
                because("ASM supports Java 25 class files (major version 69)")
            }
        }
        for (module in listOf("byte-buddy", "byte-buddy-agent")) {
            testImplementation("net.bytebuddy:$module:${libs.versions.byte.buddy.get()}") {
                because("MockK instrumentation must support the Java 25 host runtime")
            }
        }
    }

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.rules)
}
