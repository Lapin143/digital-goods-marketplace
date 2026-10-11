// Ядро расширения Keycloak «вход по SMS» (infra/keycloak/sms-otp): классы без зависимостей от Keycloak и их модульные тесты.
// Адаптеры Keycloak (аутентификаторы, фабрики, тема страниц) здесь не компилируются: им нужны библиотеки образа Keycloak, их собирает
// docker/Dockerfile.keycloak (javac --release 21 против lib/lib/main образа), а проверяет задание keycloak на настоящем Keycloak.
plugins {
    id("dgm.java-conventions")
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))
            include("dgm/keycloak/smsotp/core/**")
        }
    }
    test {
        java {
            setSrcDirs(listOf("test"))
        }
    }
}
