package dgm.catalog.sellers.apikey.domain;

import dgm.catalog.sellers.domain.FieldViolation;
import java.util.List;

/**
 * Тело запроса выпуска: имя ключа, от 1 до 60 знаков после обрезки пробелов по краям (ограничение {@code ck_api_key_name_len} считает знаки,
 * а не байты). Управляющих знаков в имени нет: имя попадает в списки и журналы, перевод строки в нём ломал бы строку журнала.
 */
public record ApiKeyForm(String name) {

    public static final int MAX_NAME = 60;

    public List<FieldViolation> violations() {
        if (name == null || name.isEmpty()) {
            return List.of(new FieldViolation("/name", "required", "Укажите имя ключа."));
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            return List.of(new FieldViolation("/name", "too_long", "Имя длиннее " + MAX_NAME + " знаков."));
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            return List.of(new FieldViolation("/name", "invalid_format", "В имени не должно быть управляющих знаков."));
        }
        return List.of();
    }
}
