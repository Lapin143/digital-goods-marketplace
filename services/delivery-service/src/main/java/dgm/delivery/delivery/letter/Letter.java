package dgm.delivery.delivery.letter;

import java.util.Objects;

/**
 * Письмо с ключами: тема и текст. Текст содержит значения ключей, поэтому {@link #toString()} показывает только длину, а в журнал, трассу и
 * метрики письмо не передаётся. Живёт до конца одной попытки.
 */
public final class Letter {

    private final String subject;
    private final String text;

    public Letter(String subject, String text) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.text = Objects.requireNonNull(text, "text");
    }

    public String subject() {
        return subject;
    }

    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return "Letter[" + text.length() + " знаков]";
    }
}
