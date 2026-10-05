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

    // Tomcat не ниже версии с исправлением уязвимостей (gradle/libs.versions.toml, tomcat-security-floor). Ограничение побеждает версию из BOM,
    // если она ниже, и не мешает, когда BOM новее. Шлюз на Netty эти библиотеки не использует, для него ограничение ничего не меняет.
    constraints {
        val floor = catalog.findVersion("tomcat-security-floor").get().requiredVersion
        for (name in listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")) {
            add("implementation", "org.apache.tomcat.embed:$name:$floor") {
                because("уязвимости Tomcat ниже $floor (Trivy)")
            }
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
