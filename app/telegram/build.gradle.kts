plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.rosyidputrar.duobackup.telegram"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit4)
}
