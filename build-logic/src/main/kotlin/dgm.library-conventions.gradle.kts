// Библиотека (service-kit): общие правила Java плюс java-library для разделения api и implementation
// и java-test-fixtures: тестовые помощники и правила ArchUnit лежат в фикстурах, в рабочий jar не попадают, сервисы подключают их
// строкой testImplementation(testFixtures(project(":libs:service-kit"))).
plugins {
    `java-library`
    `java-test-fixtures`
    id("dgm.java-conventions")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Версии из BOM нужны и фикстурам: платформа java-conventions объявлена только для implementation
    testFixturesImplementation(platform(catalog.findLibrary("spring-boot-dependencies").get()))
}
