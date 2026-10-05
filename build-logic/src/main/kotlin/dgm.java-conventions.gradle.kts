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
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Модульные тесты идут в задаче test без тега integration. Интеграционные (тег integration) работают с поднятым стендом
// (make up SET=dev-min DEBUG=1) и запускаются отдельной задачей integrationTest, в check она не входит.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Один стенд на все проекты: интеграционные задачи идут по одной (StandLock), остальные задачи сборки параллельны
val standLock = gradle.sharedServices.registerIfAbsent("standLock", StandLock::class.java) {
    maxParallelUsages.set(1)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Интеграционные тесты на поднятом стенде (тег integration)"
    group = "verification"
    usesService(standLock)
    // Результат зависит от стенда, а не только от входов задачи; образцы ответов (build/contract-samples) в выходы не входят.
    // Поэтому задача не бывает актуальной и не берётся из кэша сборки.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("тесты идут на живом стенде") { true }
    val testSources = sourceSets.getByName("test")
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
}
