package dgm.platform.notification.letter;

/**
 * Готовое письмо: тема и текст. Текст содержит имя получателя и значения параметров, поэтому в журнал письмо не попадает.
 *
 * @param subject тема
 * @param text    текст
 */
public record Letter(String subject, String text) {

    @Override
    public String toString() {
        return "Letter[" + subject + "]";
    }
}
