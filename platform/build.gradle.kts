plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":core"))
    implementation("net.java.dev.jna:jna:5.19.1")
}
