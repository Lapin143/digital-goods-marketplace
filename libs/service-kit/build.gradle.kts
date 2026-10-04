// Общий каркас сервисов (c4-components.md, раздел 2). Версии библиотек берутся из BOM, см. dgm.java-conventions.
plugins {
    id("dgm.library-conventions")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Эти библиотеки есть в каждом сервисе (spring-boot-starter-web): в jar каркаса они не попадают
val provided = listOf(
    "jakarta.servlet:jakarta.servlet-api",
    "org.springframework:spring-web",
    "org.springframework:spring-context",
)

dependencies {
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("org.apache.kafka:kafka-clients")
    api("io.micrometer:micrometer-core")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    provided.forEach {
        compileOnly(it)
        testImplementation(it)
    }
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.postgresql:postgresql")

    // Фикстуры: правила ArchUnit, часы, токены и сертификаты для тестов, доступ к стенду
    testFixturesApi(catalog.findLibrary("archunit-junit5").get())
    testFixturesApi("com.nimbusds:nimbus-jose-jwt")
    testFixturesApi("org.springframework:spring-jdbc")
    testFixturesApi("org.postgresql:postgresql")
}
