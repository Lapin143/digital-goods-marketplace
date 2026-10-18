plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))
    // Очередь сроков резерва (ADR-012) работает на Redis: клиент в каркасе compileOnly, сервис добавляет Lettuce сам (версия от BOM Spring Boot)
    implementation("io.lettuce:lettuce-core")

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
    // Канарейка ST-01 перехватывает журнал сервиса приложением Logback прямо в тесте
    testImplementation("ch.qos.logback:logback-classic")
}
