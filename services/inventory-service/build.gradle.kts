plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
    // Канарейка ST-01 перехватывает журнал сервиса приложением Logback прямо в тесте
    testImplementation("ch.qos.logback:logback-classic")
}
