plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

// Build logic runs on the invoking JDK; its class files target Gradle's Java 17 minimum.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

apply(from = "../repositories.gradle.kts")

dependencies {
    // Gradle Plugins
    implementation(libs.agp)
    implementation(libs.kotlin.gradle)
}
