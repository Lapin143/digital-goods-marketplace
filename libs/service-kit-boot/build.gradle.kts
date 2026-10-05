// Автонастройка каркаса для сервисов Spring Boot (c4-components.md, раздел 2): свойства dgm.*, пулы базы под ролями модулей,
// миграции при старте, фильтры токена и вызывающего, публикатор Outbox, потребитель событий, проверка готовности.
// Чистый каркас без Spring Boot лежит в :libs:service-kit.
plugins {
    id("dgm.library-conventions")
}

dependencies {
    api(project(":libs:service-kit"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.zaxxer:HikariCP")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Фикстуры для интеграционных тестов сервисов на стенде: клиент по mTLS, свойства подключения, проверка токена с тестовым ключом
    testFixturesApi(testFixtures(project(":libs:service-kit")))
    testFixturesApi("org.springframework:spring-test")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":libs:service-kit")))
}
