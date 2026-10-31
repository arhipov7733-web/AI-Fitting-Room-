plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android { namespace="com.example.aifittingroom"; compileSdk=35
    defaultConfig { applicationId="com.example.aifittingroom"; minSdk=29; targetSdk=35; versionCode=6; versionName="0.6" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
