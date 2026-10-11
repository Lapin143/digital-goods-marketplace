package dgm.platform.identity.otp.service;

/** Источник одноразовых кодов. В работе криптостойкий генератор ({@code SecureRandom}), в тестах заданная последовательность. */
@FunctionalInterface
public interface CodeSource {

    /** Новый код: ровно шесть цифр, ведущие нули сохраняются. */
    String next();
}
