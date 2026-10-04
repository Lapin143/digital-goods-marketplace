import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Общие правила для всех Java-модулей: Java 25, предупреждения компилятора считаются ошибками, JUnit 5, ArchUnit.
plugins {
    java
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Версии библиотек Spring берутся из BOM, явных версий в модулях нет
    implementation(platform(catalog.findLibrary("spring-boot-dependencies").get()))

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(catalog.findLibrary("archunit-junit5").get())
    // Gradle 9 не добавляет запуск тестов JUnit сам, нужен явный launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
