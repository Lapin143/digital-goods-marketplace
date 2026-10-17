plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))
    // Клиент Redis каркаса (кэш карточек витрины): KitRedisAutoConfiguration включается при наличии Lettuce и dgm.redis.host
    implementation("io.lettuce:lettuce-core")

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
}
