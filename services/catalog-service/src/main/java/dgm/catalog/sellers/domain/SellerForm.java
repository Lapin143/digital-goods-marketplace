package dgm.catalog.sellers.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Анкета продавца. Любое поле может быть пустым, пока заявка в черновике (частичное сохранение, US-2.1); для отправки нужны все.
 * Реквизиты выплат хранятся в четырёх полях, в контракте они вложены в {@code payoutDetails}.
 */
public record SellerForm(SellerType sellerType, String name, String payoutRecipientName, String payoutBankName, String payoutBik,
                         String payoutAccountNumber, String assortmentDescription) {

    public static final int MAX_NAME = 200;
    public static final int MAX_ASSORTMENT = 2000;
    private static final Pattern BIK = Pattern.compile("^[0-9]{9}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{20}$");

    public static SellerForm empty() {
        return new SellerForm(null, null, null, null, null, null, null);
    }

    /** Нарушения формата заполненных полей. Пустые (не заданные) поля нарушением не считаются. */
    public List<FieldViolation> violations() {
        List<FieldViolation> found = new ArrayList<>();
        text(found, "/name", name, MAX_NAME);
        text(found, "/payoutDetails/recipientName", payoutRecipientName, MAX_NAME);
        text(found, "/payoutDetails/bankName", payoutBankName, MAX_NAME);
        pattern(found, "/payoutDetails/bik", payoutBik, BIK, "БИК это 9 цифр.");
        pattern(found, "/payoutDetails/accountNumber", payoutAccountNumber, ACCOUNT, "Расчётный счёт это 20 цифр.");
        text(found, "/assortmentDescription", assortmentDescription, MAX_ASSORTMENT);
        return found;
    }

    /** Поля, которых не хватает для отправки заявки (код {@code required}). */
    public List<FieldViolation> missing() {
        List<FieldViolation> found = new ArrayList<>();
        required(found, "/sellerType", sellerType);
        required(found, "/name", name);
        required(found, "/payoutDetails/recipientName", payoutRecipientName);
        required(found, "/payoutDetails/bankName", payoutBankName);
        required(found, "/payoutDetails/bik", payoutBik);
        required(found, "/payoutDetails/accountNumber", payoutAccountNumber);
        required(found, "/assortmentDescription", assortmentDescription);
        return found;
    }

    public boolean complete() {
        return missing().isEmpty();
    }

    public boolean hasPayoutDetails() {
        return payoutRecipientName != null || payoutBankName != null || payoutBik != null || payoutAccountNumber != null;
    }

    private static void required(List<FieldViolation> found, String pointer, Object value) {
        if (value == null) {
            found.add(new FieldViolation(pointer, "required", "Поле обязательно для отправки заявки."));
        }
    }

    private static void text(List<FieldViolation> found, String pointer, String value, int max) {
        if (value == null) {
            return;
        }
        int length = value.codePointCount(0, value.length());
        if (length == 0) {
            found.add(new FieldViolation(pointer, "too_short", "Поле не может быть пустым."));
        } else if (length > max) {
            found.add(new FieldViolation(pointer, "too_long", "Не больше " + max + " знаков."));
        }
    }

    private static void pattern(List<FieldViolation> found, String pointer, String value, Pattern pattern, String detail) {
        if (value != null && !pattern.matcher(value).matches()) {
            found.add(new FieldViolation(pointer, "invalid_format", detail));
        }
    }
}
