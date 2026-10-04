plugins {
    // Root project only declares plugin versions for the subprojects.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.ksp) apply false
}
