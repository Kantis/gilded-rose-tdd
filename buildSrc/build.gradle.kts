plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
}
