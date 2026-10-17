package dgm.catalog.sellers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Анкета: частичная допустима, нарушения формата названы указателем и кодом, полнота проверяется отдельно. */
class SellerFormTest {

    private static final SellerForm FULL = new SellerForm(SellerType.LEGAL_ENTITY, "ООО «Ромашка»", "ООО «Ромашка»", "Банк «Пример»", "044525000",
            "40702810000000000001", "Подписки и лицензии");

    private static List<String> pointers(List<FieldViolation> violations) {
        return violations.stream().map(FieldViolation::pointer).toList();
    }

    @Test
    void emptyFormHasNoFormatViolationsButEverythingIsMissing() {
        assertTrue(SellerForm.empty().violations().isEmpty());
        assertEquals(List.of("/sellerType", "/name", "/payoutDetails/recipientName", "/payoutDetails/bankName", "/payoutDetails/bik",
                "/payoutDetails/accountNumber", "/assortmentDescription"), pointers(SellerForm.empty().missing()));
        assertTrue(SellerForm.empty().missing().stream().allMatch(v -> v.code().equals("required")));
        assertFalse(SellerForm.empty().complete());
    }

    @Test
    void fullFormIsCompleteAndValid() {
        assertTrue(FULL.violations().isEmpty());
        assertTrue(FULL.missing().isEmpty());
        assertTrue(FULL.complete());
        assertTrue(FULL.hasPayoutDetails());
    }

    @Test
    void partialFormReportsOnlyWhatIsMissing() {
        SellerForm partial = new SellerForm(SellerType.SELF_EMPLOYED, "Студия", null, "Банк", null, null, null);
        assertTrue(partial.violations().isEmpty());
        assertEquals(List.of("/payoutDetails/recipientName", "/payoutDetails/bik", "/payoutDetails/accountNumber", "/assortmentDescription"),
                pointers(partial.missing()));
        assertTrue(partial.hasPayoutDetails());
        assertFalse(new SellerForm(SellerType.SELF_EMPLOYED, "Студия", null, null, null, null, null).hasPayoutDetails());
    }

    @Test
    void textLengthsAreCountedInCodePoints() {
        String name200 = "я".repeat(200);
        String emoji200 = "😀".repeat(200);
        assertTrue(new SellerForm(null, name200, null, null, null, null, null).violations().isEmpty());
        assertTrue(new SellerForm(null, emoji200, null, null, null, null, null).violations().isEmpty(), "200 знаков, хотя 400 единиц UTF-16");
        List<FieldViolation> tooLong = new SellerForm(null, "я".repeat(201), null, null, null, null, null).violations();
        assertEquals(List.of("/name"), pointers(tooLong));
        assertEquals("too_long", tooLong.get(0).code());
        assertEquals("too_long", new SellerForm(null, null, "я".repeat(201), null, null, null, null).violations().get(0).code());
        assertEquals("too_long", new SellerForm(null, null, null, "я".repeat(201), null, null, null).violations().get(0).code());
    }

    @Test
    void assortmentDescriptionAllowsTwoThousandCharacters() {
        assertTrue(new SellerForm(null, null, null, null, null, null, "я".repeat(2000)).violations().isEmpty());
        List<FieldViolation> tooLong = new SellerForm(null, null, null, null, null, null, "я".repeat(2001)).violations();
        assertEquals(List.of("/assortmentDescription"), pointers(tooLong));
        assertEquals("too_long", tooLong.get(0).code());
    }

    @Test
    void emptyStringIsTooShortNotMissing() {
        List<FieldViolation> violations = new SellerForm(null, "", "", "", null, null, "").violations();
        assertEquals(List.of("/name", "/payoutDetails/recipientName", "/payoutDetails/bankName", "/assortmentDescription"), pointers(violations));
        assertTrue(violations.stream().allMatch(v -> v.code().equals("too_short")));
    }

    @Test
    void bikIsNineDigitsAndAccountIsTwentyDigits() {
        for (String bik : new String[] {"04452500", "0445250000", "04452500A", "044 525 00", " 044525000"}) {
            List<FieldViolation> violations = new SellerForm(null, null, null, null, bik, null, null).violations();
            assertEquals(List.of("/payoutDetails/bik"), pointers(violations), bik);
            assertEquals("invalid_format", violations.get(0).code(), bik);
        }
        for (String account : new String[] {"4081781000000000000", "408178100000000000011", "4081781000000000000A"}) {
            List<FieldViolation> violations = new SellerForm(null, null, null, null, null, account, null).violations();
            assertEquals(List.of("/payoutDetails/accountNumber"), pointers(violations), account);
            assertEquals("invalid_format", violations.get(0).code(), account);
        }
        assertTrue(new SellerForm(null, null, null, null, "044525000", "40817810000000000001", null).violations().isEmpty());
    }

    @Test
    void severalViolationsAreAllReported() {
        SellerForm bad = new SellerForm(null, "", null, null, "1", "2", "я".repeat(2001));
        assertEquals(List.of("/name", "/payoutDetails/bik", "/payoutDetails/accountNumber", "/assortmentDescription"), pointers(bad.violations()));
    }
}
