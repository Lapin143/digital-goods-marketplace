// API Gateway (ADR-021): Spring Cloud Gateway на WebFlux. Шлюз тонкий: маршруты, токен, область, лимиты, заголовки. Баз и Kafka у него нет,
// поэтому из каркаса берутся только чистые части (правила маршрутов, проверка токена, ответы Problem, трассировка, проба готовности).
plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit")) {
        exclude(group = "org.springframework", module = "spring-jdbc")
        exclude(group = "org.apache.kafka")
    }
    implementation(platform(libs.spring.cloud.dependencies))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(testFixtures(project(":libs:service-kit")))
}
