plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))
    // Клиент Redis подключается в каркасе как compileOnly: сервис, который работает с Redis, добавляет Lettuce сам (версия от BOM Spring Boot)
    implementation("io.lettuce:lettuce-core")

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
}
