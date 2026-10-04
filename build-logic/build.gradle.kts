plugins {
    `kotlin-dsl`
}

dependencies {
    // Плагин Spring Boot нужен скриптам-плагинам, которые его применяют
    implementation(libs.spring.boot.gradle.plugin)
}
