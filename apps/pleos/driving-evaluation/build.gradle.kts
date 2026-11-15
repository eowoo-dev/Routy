plugins {
    `java-library`
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.eowoo.routy"

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
application { mainClass.set("org.eowoo.routy.evaluation.ReplayKt") }
dependencies {
    implementation(libs.moshi.core)
    testImplementation(kotlin("test-junit"))
}

// A separate report keeps the full regression run intact while exercising the
// disk-backed pipeline under a substantially smaller heap than Android's.
tasks.register<Test>("archiveMemoryTest") {
    description = "Two-hour disk archive regressions with a 96MiB heap"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "96m"
    filter { includeTestsMatching("*TripArchiveTest") }
    mustRunAfter(tasks.test)
}
