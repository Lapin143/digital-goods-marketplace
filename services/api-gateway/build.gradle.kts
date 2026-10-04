plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(platform(libs.spring.cloud.dependencies))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
