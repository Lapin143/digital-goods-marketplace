plugins {
    id("dgm.boot-app-conventions")
}

dependencies {
    implementation(project(":libs:service-kit-boot"))

    testImplementation(testFixtures(project(":libs:service-kit")))
    testImplementation(testFixtures(project(":libs:service-kit-boot")))
}
