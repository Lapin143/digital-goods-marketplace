package dgm.kit.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Секреты-файлы как свойства приложения. Нужны там, где библиотека принимает пароль только строкой настройки (например, клиент Redis
 * в Spring Boot: {@code spring.data.redis.password}). Значение читается здесь, в пакете, которому разрешено читать {@link SecretValue},
 * и попадает только в окружение процесса: в образ, в переменные окружения контейнера и в Git оно не попадает.
 */
public final class SecretProperties {

    private SecretProperties() {
    }

    /**
     * Читает файлы секретов каталога и возвращает свойства «имя свойства, значение».
     *
     * @param directory      каталог секретов (в контейнере /run/secrets)
     * @param propertyToFile имя свойства и имя файла секрета в каталоге
     * @return свойства только тех секретов, чьи файлы есть; нет файла (тест без секретов), нет и свойства
     */
    public static Map<String, Object> fromFiles(Path directory, Map<String, String> propertyToFile) {
        Map<String, Object> result = new LinkedHashMap<>();
        propertyToFile.forEach((property, file) -> {
            Path path = directory.resolve(file);
            if (Files.isRegularFile(path)) {
                result.put(property, SecretFiles.read(path).reveal());
            }
        });
        return result;
    }
}
