import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        clion("2026.2.2")
        bundledPlugin("com.intellij.clion")

        testFramework(TestFrameworkType.Platform)
    }
}