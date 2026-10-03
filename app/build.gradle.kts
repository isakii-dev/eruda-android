import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = "2.0.0"

android {
    namespace = "io.liriliri.eruda"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.liriliri.eruda"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.core:core-splashscreen:1.0.0")
    implementation("androidx.webkit:webkit:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

// `archivesBaseName` não existe no DSL público do AGP 8 (só o Groovy via reflect),
// então o nome do APK sai da variant API antiga, ainda suportada no AGP 8.
configure<AppExtension> {
    applicationVariants.all {
        val variantName = name
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "eruda-v$appVersion-$variantName.apk"
        }
    }
}
