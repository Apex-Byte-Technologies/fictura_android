plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// The one place the SDK version lives: the published artifact and the version the SDK reports.
val sdkVersion = "0.2.0"

android {
    namespace = "co.fictura.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Optional: when the app includes Google's Install Referrer library, the SDK reads the referrer.
    compileOnly("com.android.installreferrer:installreferrer:2.2")
    testImplementation("junit:junit:4.13.2")
}

mavenPublishing {
    // Uploads to the Central Portal; the first releases are checked and published by hand on its Deployments page.
    publishToMavenCentral()
    // Maven Central needs signed files. Locally and in PR CI there is no key, so publishToMavenLocal still works.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("co.fictura", "fictura-android", sdkVersion)
    pom {
        name.set("Fictura Android SDK")
        description.set("Fictura for native Android apps: events, server-driven paywalls, placements, experiments, purchases and error reporting.")
        inceptionYear.set("2026")
        url.set("https://github.com/Apex-Byte-Technologies/fictura_android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("apex-byte-technologies")
                name.set("Apex Byte Technologies")
                url.set("https://fictura.co")
            }
        }
        scm {
            url.set("https://github.com/Apex-Byte-Technologies/fictura_android")
            connection.set("scm:git:https://github.com/Apex-Byte-Technologies/fictura_android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Apex-Byte-Technologies/fictura_android.git")
        }
    }
}
