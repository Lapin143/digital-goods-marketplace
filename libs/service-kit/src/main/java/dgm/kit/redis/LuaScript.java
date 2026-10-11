package dgm.kit.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Скрипт Lua для Redis. Выполняется по SHA-1 ({@code EVALSHA}): при первом вызове или после перезапуска Redis клиент загружает текст
 * ({@code SCRIPT LOAD}) и повторяет вызов. Скрипт получает ключи только через {@code KEYS}: права пользователя Redis ограничены
 * префиксом ключей (ACL, ADR-022).
 */
public final class LuaScript {

    /** Что скрипт возвращает: строку (или пусто) либо целое число. */
    public enum Result { TEXT, INTEGER }

    private final String name;
    private final String source;
    private final Result result;
    private final String sha1;

    public LuaScript(String name, String source, Result result) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.result = Objects.requireNonNull(result, "result");
        this.sha1 = sha1Hex(source);
    }

    public String name() {
        return name;
    }

    public String source() {
        return source;
    }

    public Result result() {
        return result;
    }

    /** SHA-1 текста скрипта в hex, как его считает Redis. */
    public String sha1() {
        return sha1;
    }

    static String sha1Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 недоступен в этой JVM", e);
        }
    }

    @Override
    public String toString() {
        return "LuaScript[" + name + "]";
    }
}
