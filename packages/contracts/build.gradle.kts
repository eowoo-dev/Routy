plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin")) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-okhttp4")
    inputSpec.set(layout.projectDirectory.file("openapi/routy.yaml").asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    packageName.set("com.noonmaru.routy.contracts")
    apiPackage.set("com.noonmaru.routy.contracts.api")
    modelPackage.set("com.noonmaru.routy.contracts.model")
    configOptions.set(mapOf("serializationLibrary" to "moshi", "dateLibrary" to "java8", "omitGradleWrapper" to "true"))
    globalProperties.set(mapOf("apiTests" to "false", "modelTests" to "false", "apiDocs" to "false", "modelDocs" to "false"))
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerate")) }

dependencies {
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
}
