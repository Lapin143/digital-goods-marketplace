plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
