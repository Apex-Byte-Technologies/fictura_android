plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.fictura.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "co.fictura.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // ./gradlew :demo:installDebug -PficturaKey=gk_... -PficturaApi=http://10.0.2.2:4000
        buildConfigField("String", "FICTURA_KEY", "\"${project.findProperty("ficturaKey") ?: "gk_demo"}\"")
        buildConfigField("String", "FICTURA_API", "\"${project.findProperty("ficturaApi") ?: "http://10.0.2.2:4000"}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":fictura")) }
