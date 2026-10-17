package dgm.catalog.catalog.seller.domain;

import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.withDescription;
import static dgm.catalog.catalog.seller.Products.withIssuance;
import static dgm.catalog.catalog.seller.Products.withPlatform;
import static dgm.catalog.catalog.seller.Products.withPrice;
import static dgm.catalog.catalog.seller.Products.withRegions;
import static dgm.catalog.catalog.seller.Products.withTitle;
import static dgm.catalog.catalog.seller.Products.withType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.domain.FieldViolation;
import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductFormTest {

    private static List<String> pointers(ProductForm form) {
        return form.violations().stream().map(FieldViolation::pointer).toList();
    }

    private static String code(ProductForm form, String pointer) {
        return form.violations().stream().filter(v -> v.pointer().equals(pointer)).map(FieldViolation::code).findFirst().orElse(null);
    }

    @Test
    void sampleFormHasNoViolations() {
        assertEquals(List.of(), form().violations());
    }

    @Test
    void emptyRegionListMeansAllRegionsAndIsValid() {
        assertEquals(List.of(), withRegions(form(), List.of()).violations());
    }

    @Test
    void missingRegionListIsRequiredButEmptyOneIsNot() {
        assertEquals("required", code(withRegions(form(), null), "/activationRegions"));
    }

    @Test
    void textFieldsAreBoundedByCodePoints() {
        assertEquals("too_short", code(withTitle(form(), ""), "/title"));
        assertEquals("too_long", code(withTitle(form(), "а".repeat(201)), "/title"));
        assertEquals(List.of(), withTitle(form(), "а".repeat(200)).violations());
        assertEquals("too_long", code(withDescription(form(), "б".repeat(5001)), "/description"));
        assertEquals("too_short", code(withDescription(form(), ""), "/description"));
        assertEquals("too_long", code(withPlatform(form(), "x".repeat(65)), "/platform"));
        assertEquals("required", code(withTitle(form(), null), "/title"));
        // эмодзи занимает две единицы UTF-16, но один знак: 200 таких знаков в пределе
        assertEquals(List.of(), withTitle(form(), "😀".repeat(200)).violations());
        assertEquals("too_long", code(withTitle(form(), "😀".repeat(201)), "/title"));
    }

    @Test
    void priceMustBePositiveAndFitJsonSafeInteger() {
        assertEquals("out_of_range", code(withPrice(form(), 0), "/price/amount"));
        assertEquals("out_of_range", code(withPrice(form(), -1), "/price/amount"));
        assertEquals(List.of(), withPrice(form(), 1).violations());
        assertEquals(List.of(), withPrice(form(), ProductForm.MAX_PRICE).violations());
        assertEquals("out_of_range", code(withPrice(form(), ProductForm.MAX_PRICE + 1), "/price/amount"));
    }

    @Test
    void regionsAreIsoCodesWithoutRepeatsAndAtMost250() {
        assertEquals("invalid_format", code(withRegions(form(), List.of("ru")), "/activationRegions/0"));
        assertEquals("invalid_format", code(withRegions(form(), List.of("RUS")), "/activationRegions/0"));
        assertEquals("not_unique", code(withRegions(form(), List.of("RU", "KZ", "RU")), "/activationRegions/2"));
        List<String> many = new java.util.ArrayList<>();
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'A'; b <= 'Z'; b++) {
                many.add("" + a + b);
            }
        }
        assertEquals("too_long", code(withRegions(form(), many.subList(0, 251)), "/activationRegions"));
        assertEquals(List.of(), withRegions(form(), many.subList(0, 250)).violations());
    }

    @Test
    void sellerApiIsNotAvailableInThisRelease() {
        assertEquals("unknown_value", code(withIssuance(form(), IssuanceMethod.SELLER_API), "/issuanceMethod"));
        assertEquals("required", code(withIssuance(form(), null), "/issuanceMethod"));
        assertEquals("required", code(withType(form(), null), "/productType"));
    }

    @Test
    void severalViolationsAreReportedTogether() {
        ProductForm bad = new ProductForm("", null, null, "x".repeat(70), null, 0L, null);
        assertEquals(List.of("/title", "/description", "/productType", "/platform", "/activationRegions", "/price/amount", "/issuanceMethod"),
                pointers(bad));
    }

    @Test
    void differencesListNamesInContractOrderAndIgnoreRegionOrder() {
        ProductForm base = form();
        assertEquals(List.of(), base.differences(withRegions(base, List.of("KZ", "RU"))));
        assertEquals(List.of("title"), base.differences(withTitle(base, "Другое")));
        assertEquals(List.of("description"), base.differences(withDescription(base, "Другое")));
        assertEquals(List.of("productType"), base.differences(withType(base, ProductType.SUBSCRIPTION)));
        assertEquals(List.of("platform"), base.differences(withPlatform(base, "GOG")));
        assertEquals(List.of("activationRegions"), base.differences(withRegions(base, List.of())));
        assertEquals(List.of("price"), base.differences(withPrice(base, 1)));
        ProductForm all = new ProductForm("a", "b", ProductType.GIFT_CARD, "c", List.of("US"), 5L, IssuanceMethod.SELLER_API);
        assertEquals(List.of("title", "description", "productType", "platform", "activationRegions", "price", "issuanceMethod"), base.differences(all));
    }

    @Test
    void onlyTitleDescriptionAndTypeNeedModeration() {
        assertTrue(ProductForm.needsModeration(List.of("title")));
        assertTrue(ProductForm.needsModeration(List.of("price", "description")));
        assertTrue(ProductForm.needsModeration(List.of("productType")));
        assertFalse(ProductForm.needsModeration(List.of("price", "platform", "activationRegions", "issuanceMethod")));
        assertFalse(ProductForm.needsModeration(List.of()));
    }
}
