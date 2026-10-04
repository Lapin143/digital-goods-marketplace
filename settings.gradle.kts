// Один мультипроект на весь репозиторий (ADR-023). Версии библиотек: gradle/libs.versions.toml.
// Общие правила сборки: build-logic (плагины dgm.*).

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "digital-goods-marketplace"

include(
    ":libs:service-kit",
    ":services:api-gateway",
    ":services:catalog-service",
    ":services:inventory-service",
    ":services:order-service",
    ":services:payment-service",
    ":services:delivery-service",
    ":services:platform-service",
)
