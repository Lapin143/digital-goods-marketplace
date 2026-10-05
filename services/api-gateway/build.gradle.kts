plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit"))
    implementation(platform(libs.spring.cloud.dependencies))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
}

tasks.register("printClasspath") {
    doLast {
        configurations.getByName("runtimeClasspath").files.forEach { println("JAR " + it) }
        configurations.getByName("testRuntimeClasspath").files.forEach { println("TJAR " + it) }
    }
}
