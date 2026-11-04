plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))
    // Клиент Redis каркаса (кэш карточек витрины): KitRedisAutoConfiguration включается при наличии Lettuce и dgm.redis.host
    implementation("io.lettuce:lettuce-core")

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
    // Канарейка API-ключа (TC-953) перехватывает журнал сервиса приложением Logback прямо в тесте
    testImplementation("ch.qos.logback:logback-classic")
}
