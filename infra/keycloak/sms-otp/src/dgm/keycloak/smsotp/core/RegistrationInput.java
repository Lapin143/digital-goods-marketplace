package dgm.keycloak.smsotp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Проверка полей формы регистрации, которая не зависит от того, занят ли адрес: по этим проверкам страница отвечает одинаково для любого
 * e-mail (нейтральность ответа, US-01, ST-06). Проверка политики пароля делается в Keycloak по политике realm, адрес на уникальность
 * проверяется только после того, как поля признаны годными, и покупателю об этом не сообщается.
 */
public final class RegistrationInput {

    /** Ошибка формы: поле и ключ сообщения в стандартных сообщениях Keycloak. */
    public record Problem(String field, String messageKey) {
    }

    /** Результат проверки: приведённый адрес и ошибки (пусто, если форма годна). */
    public record Result(String email, List<Problem> problems) {
        public boolean valid() {
            return problems.isEmpty();
        }
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s.]{2,}$");
    private static final int MAX_EMAIL = 255;
    private static final int MAX_PASSWORD = 128;

    private RegistrationInput() {
    }

    /** Приведение адреса: пробелы по краям убираются, регистр букв не различается (Keycloak хранит адрес в нижнем регистре). */
    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    public static Result check(String rawEmail, String password, String confirmation) {
        String email = normalizeEmail(rawEmail);
        List<Problem> problems = new ArrayList<>();
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            problems.add(new Problem("email", "invalidEmailMessage"));
        }
        if (password == null || password.isEmpty()) {
            problems.add(new Problem("password", "missingPasswordMessage"));
        } else if (password.length() > MAX_PASSWORD) {
            problems.add(new Problem("password", "invalidPasswordMessage"));
        } else if (!password.equals(confirmation)) {
            problems.add(new Problem("password-confirm", "invalidPasswordConfirmMessage"));
        }
        return new Result(email, List.copyOf(problems));
    }
}
