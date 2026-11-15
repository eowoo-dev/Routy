import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

group = "org.eowoo.routy"
val identity = groovy.json.JsonSlurper().parse(rootProject.file("../../packages/contracts/identity.json")) as Map<*, *>
val team = identity["team"] as Map<*, *>
val service = identity["service"] as Map<*, *>
val applicationIds = identity["applicationIds"] as Map<*, *>
val vehiclePackage = applicationIds["vehicle"] as String
val releaseApiOrigin = providers.gradleProperty("routyApiBaseUrl").orElse(service["webOrigin"] as String)
val apiOrigin = providers.gradleProperty("routyApiBaseUrl").orElse("http://127.0.0.1:3000")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    sourceSets.getByName("main").assets.srcDir("../../../packages/contracts/design")
    useLibrary("android.car", false)
    namespace = vehiclePackage
    compileSdk = 36
    defaultConfig {
        applicationId = vehiclePackage
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
        resValue("string", "team_name", team["nameKo"] as String)
        resValue("string", "team_name_en", team["nameEn"] as String)
        resValue("string", "team_domain", team["domain"] as String)
        buildConfigField("String", "API_BASE_URL", quoted(apiOrigin.get().trimEnd('/')))
        buildConfigField("String", "VEHICLE_SDK_VERSION", quoted(libs.versions.vehicle.get()))
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-dev" }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", quoted(releaseApiOrigin.get().trimEnd('/')))
        }
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
        val url = URI(releaseApiOrigin.get())
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null
            && url.rawQuery == null && url.fragment == null && url.path in listOf("", "/")) {
            "Release requires -ProutyApiBaseUrl=https://routy.eowoo.org (origin without /api)."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseApi) }

dependencies {
    implementation(libs.zxing.core)
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
