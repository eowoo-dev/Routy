plugins {
    `java-library`
    application
    alias(libs.plugins.kotlin.jvm)
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
application { mainClass.set("com.noonmaru.routy.evaluation.ReplayKt") }
dependencies {
    implementation(libs.moshi.core)
    testImplementation(kotlin("test-junit"))
}
