import org.springframework.boot.gradle.tasks.bundling.BootJar

// Приложение Spring Boot (сервис или шлюз): исполняемый jar со слоями, actuator и тестовые зависимости.
plugins {
    id("dgm.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<BootJar>("bootJar") {
    // Имя файла не зависит от версии: его ждёт Dockerfile (docker/Dockerfile.service)
    archiveFileName = "${project.name}.jar"
}

tasks.named<Jar>("jar") {
    enabled = false
}
