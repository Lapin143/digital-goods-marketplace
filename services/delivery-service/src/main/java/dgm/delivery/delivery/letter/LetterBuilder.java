package dgm.delivery.delivery.letter;

import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.kit.secret.SecretValue;
import java.util.List;

/**
 * Сборщик письма (компонент {@code letter-builder}, INV-19, INV-20): формирует тему и текст в памяти из названия товара и значений ключей и
 * проверяет, что значений ровно столько, сколько в заказе. Это единственное место сервиса, где значения читаются открытым текстом. Адрес берёт
 * не он: адресат остаётся снимком в выдаче.
 */
public final class LetterBuilder {

    private static final int SUBJECT_TITLE_LIMIT = 80;

    /**
     * @param productTitle название товара из снимка заказа
     * @param quantity     сколько ключей в заказе
     * @param keys         значения, полученные у {@code inventory-service}
     * @throws KeyCountMismatchException значений не столько, сколько в заказе
     */
    public Letter build(String productTitle, int quantity, ReleasedKeys keys) {
        if (keys.size() != quantity) {
            throw new KeyCountMismatchException(quantity, keys.size());
        }
        String title = clean(productTitle);
        StringBuilder text = new StringBuilder();
        text.append("Здравствуйте!\n\n");
        text.append("Заказ оплачен. Ваши ключи к товару «").append(title).append("» (").append(quantity).append(" шт.):\n\n");
        List<SecretValue> values = keys.values();
        for (int i = 0; i < values.size(); i++) {
            text.append(i + 1).append(". ").append(values.get(i).reveal()).append('\n');
        }
        text.append("\nХраните письмо в надёжном месте и не пересылайте его: ключ действует для любого, у кого он есть.\n");
        text.append("Если письмо пришло по ошибке или ключ не подошёл, напишите в поддержку маркетплейса из личного кабинета.\n");
        return new Letter("Ваши ключи: " + abbreviate(title), text.toString());
    }

    /** Название без управляющих символов: тема письма не должна переносить строку. */
    private static String clean(String title) {
        return title.replaceAll("\\p{Cntrl}", " ").strip();
    }

    private static String abbreviate(String title) {
        return title.length() <= SUBJECT_TITLE_LIMIT ? title : title.substring(0, SUBJECT_TITLE_LIMIT - 1) + "…";
    }
}
