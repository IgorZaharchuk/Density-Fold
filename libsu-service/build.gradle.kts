plugins {
    id("com.android.library")
}

group = "com.github.topjohnwu.libsu"

android {
    namespace = "com.topjohnwu.superuser.ipc"
    compileSdk = 34

    defaultConfig {
        minSdk = 19
        targetSdk = 34
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.6.0")
    api("com.github.topjohnwu.libsu:core:6.0.0")
}
