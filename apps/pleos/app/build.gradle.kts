import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val apiOrigin = providers.gradleProperty("routyApiBaseUrl").orElse("http://127.0.0.1:3000")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    sourceSets.getByName("main").assets.srcDir("../../../packages/contracts/design")
    useLibrary("android.car", false)
    namespace = "com.noonmaru.routy.pleos"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.noonmaru.routy.pleos"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", quoted(apiOrigin.get().trimEnd('/')))
        buildConfigField("String", "VEHICLE_SDK_VERSION", quoted(libs.versions.vehicle.get()))
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-dev" }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }

val validateReleaseApi by tasks.registering {
    doLast {
        val url = URI(apiOrigin.get())
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null
            && url.rawQuery == null && url.fragment == null && url.path in listOf("", "/")) {
            "Release requires -ProutyApiBaseUrl=https://YOUR_API_HOST (origin without /api)."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseApi) }

dependencies {
    implementation(libs.androidx.work)
    implementation(libs.moshi.core)
    testImplementation(kotlin("test-junit"))
    debugImplementation("ai.pleos.playground:FusedLocation:1.1.3")
    debugImplementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation(project(":driving-evaluation"))
    implementation(project(":contracts"))
    implementation(libs.vehicle.sdk)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
