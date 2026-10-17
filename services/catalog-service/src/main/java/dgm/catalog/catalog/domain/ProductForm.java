package dgm.catalog.catalog.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Изменяемые продавцом поля товара ({@code ProductInput}). Все поля обязательны (Р-20: пустой список регионов значит «все регионы»,
 * но сам список присутствует). Запись допускает пустые значения, чтобы контроллер мог собрать все нарушения одним ответом; домен
 * принимает только форму без нарушений.
 *
 * @param price цена за единицу в копейках, больше нуля; валюта в R1 всегда RUB
 */
public record ProductForm(String title, String description, ProductType productType, String platform, List<String> activationRegions,
                          Long price, IssuanceMethod issuanceMethod) {

    public static final int MAX_TITLE = 200;
    public static final int MAX_DESCRIPTION = 5000;
    public static final int MAX_PLATFORM = 64;
    public static final int MAX_REGIONS = 250;
    public static final long MAX_PRICE = 9_007_199_254_740_991L;
    public static final String CURRENCY = "RUB";

    private static final Pattern REGION = Pattern.compile("^[A-Z]{2}$");

    public ProductForm {
        activationRegions = activationRegions == null ? null : List.copyOf(activationRegions);
    }

    /** Нарушения правил полей: пустые, слишком длинные, не того формата, неизвестный в этом релизе способ выдачи. */
    public List<FieldViolation> violations() {
        List<FieldViolation> found = new ArrayList<>();
        text(found, "/title", title, MAX_TITLE);
        text(found, "/description", description, MAX_DESCRIPTION);
        if (productType == null) {
            found.add(new FieldViolation("/productType", "required", "Поле обязательно."));
        }
        text(found, "/platform", platform, MAX_PLATFORM);
        regions(found);
        if (price == null) {
            found.add(new FieldViolation("/price/amount", "required", "Поле обязательно."));
        } else if (price < 1 || price > MAX_PRICE) {
            found.add(new FieldViolation("/price/amount", "out_of_range", "Цена в копейках: от 1 до " + MAX_PRICE + "."));
        }
        if (issuanceMethod == null) {
            found.add(new FieldViolation("/issuanceMethod", "required", "Поле обязательно."));
        } else if (!issuanceMethod.availableInRelease()) {
            found.add(new FieldViolation("/issuanceMethod", "unknown_value", "В этом релизе доступна только выдача из пула ключей: key_pool."));
        }
        return found;
    }

    /** Имена полей, которыми эта форма отличается от {@code other}, в порядке контракта; пусто, если формы равны. */
    public List<String> differences(ProductForm other) {
        List<String> changed = new ArrayList<>();
        if (!title.equals(other.title)) {
            changed.add("title");
        }
        if (!description.equals(other.description)) {
            changed.add("description");
        }
        if (productType != other.productType) {
            changed.add("productType");
        }
        if (!platform.equals(other.platform)) {
            changed.add("platform");
        }
        if (!sameRegions(activationRegions, other.activationRegions)) {
            changed.add("activationRegions");
        }
        if (!price.equals(other.price)) {
            changed.add("price");
        }
        if (issuanceMethod != other.issuanceMethod) {
            changed.add("issuanceMethod");
        }
        return changed;
    }

    /**
     * Правка названия, описания или типа снимает опубликованный товар с витрины и возвращает на модерацию (SM-04/T6): модератор
     * проверял именно этот текст. Цена, платформа, регион и способ выдачи на модерацию не влияют (T7).
     */
    public static boolean needsModeration(List<String> changedFields) {
        return changedFields.contains("title") || changedFields.contains("description") || changedFields.contains("productType");
    }

    /** Регионы считаются множеством: порядок кодов не важен, повторов нет. */
    private static boolean sameRegions(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    private void regions(List<FieldViolation> found) {
        if (activationRegions == null) {
            found.add(new FieldViolation("/activationRegions", "required", "Поле обязательно: пустой список значит «все регионы»."));
            return;
        }
        if (activationRegions.size() > MAX_REGIONS) {
            found.add(new FieldViolation("/activationRegions", "too_long", "Не больше " + MAX_REGIONS + " регионов."));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < activationRegions.size(); i++) {
            String code = activationRegions.get(i);
            if (code == null || !REGION.matcher(code).matches()) {
                found.add(new FieldViolation("/activationRegions/" + i, "invalid_format", "Код страны ISO 3166-1 alpha-2 в верхнем регистре, например RU."));
            } else if (!seen.add(code)) {
                found.add(new FieldViolation("/activationRegions/" + i, "not_unique", "Регион указан дважды."));
            }
        }
    }

    private static void text(List<FieldViolation> found, String pointer, String value, int max) {
        if (value == null) {
            found.add(new FieldViolation(pointer, "required", "Поле обязательно."));
            return;
        }
        int length = value.codePointCount(0, value.length());
        if (length == 0) {
            found.add(new FieldViolation(pointer, "too_short", "Поле не может быть пустым."));
        } else if (length > max) {
            found.add(new FieldViolation(pointer, "too_long", "Не больше " + max + " знаков."));
        }
    }
}
