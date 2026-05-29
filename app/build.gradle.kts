plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.opencode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}

val downloadNodeJs = tasks.register("downloadNodeJs") {
    val nodeDir = file("src/main/assets/nodejs-project/nodejs")
    val nodeBin = File(nodeDir, "bin/node")

    onlyIf { !nodeBin.exists() }

    doLast {
        nodeDir.mkdirs()

        val arch = "arm64"
        val version = "22.12.0"
        val url = "https://nodejs.org/dist/v${version}/node-v${version}-android-${arch}.tar.gz"
        val tarball = File(temporaryDir, "node-android.tar.gz")

        logger.lifecycle("Downloading Node.js $version for Android $arch...")
        ant.invokeMethod("get", mapOf("src" to url, "dest" to tarball))
        ant.invokeMethod("untar", mapOf(
            "src" to tarball,
            "dest" to nodeDir,
            "compression" to "gzip",
            "strip" to 1
        ))

        val extractedBin = File(nodeDir, "bin/node")
        if (extractedBin.exists()) {
            extractedBin.setExecutable(true)
            logger.lifecycle("Node.js binary ready at ${extractedBin.absolutePath}")
        } else {
            throw GradleException("Failed to extract Node.js binary")
        }
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("mergeReleaseNativeLibs") || name.startsWith("mergeDebugNativeLibs")) {
        dependsOn(downloadNodeJs)
    }
}
