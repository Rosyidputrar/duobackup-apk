plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // -PregenerateGolden lifts @Disabled so GoldenGenerator can write missing golden files.
    if (providers.gradleProperty("regenerateGolden").isPresent) {
        systemProperty("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
        systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources/golden").asFile.path)
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
