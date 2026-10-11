import org.springframework.boot.gradle.tasks.bundling.BootJar

// Приложение Spring Boot (сервис или шлюз): исполняемый jar со слоями, actuator и тестовые зависимости.
plugins {
    id("dgm.java-conventions")
    id("org.springframework.boot")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Jackson 3 не ниже версии с исправлениями (jackson-security-floor): платформа с более высокой версией побеждает BOM Spring Boot, а когда
    // BOM новее, побеждает он. Одна платформа держит согласованными jackson-core, databind и остальные модули.
    implementation(platform(catalog.findLibrary("jackson-bom").get()))

    // Tomcat не ниже версии с исправлением уязвимостей (gradle/libs.versions.toml, tomcat-security-floor). Ограничение побеждает версию из BOM,
    // если она ниже, и не мешает, когда BOM новее. Шлюз на Netty эти библиотеки не использует, для него ограничение ничего не меняет.
    constraints {
        val floor = catalog.findVersion("tomcat-security-floor").get().requiredVersion
        for (name in listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")) {
            add("implementation", "org.apache.tomcat.embed:$name:$floor") {
                because("уязвимости Tomcat ниже $floor (Trivy)")
            }
        }

        // lz4-java приходит с клиентом Kafka; версия из BOM попала под CVE-2026-106451 (HIGH), исправление в 1.11.4 (Trivy)
        val lz4Floor = catalog.findVersion("lz4-security-floor").get().requiredVersion
        add("implementation", "at.yawk.lz4:lz4-java:$lz4Floor") {
            because("уязвимость lz4-java ниже $lz4Floor (Trivy, CVE-2026-106451)")
        }
    }
}

tasks.named<BootJar>("bootJar") {
    // Имя файла не зависит от версии: его ждёт Dockerfile (docker/Dockerfile.service)
    archiveFileName = "${project.name}.jar"
}

tasks.named<Jar>("jar") {
    enabled = false
}
