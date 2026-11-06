package dgm.catalog.catalog.seller.domain;

import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static dgm.catalog.catalog.seller.Products.sellerBlocked;
import static dgm.catalog.catalog.seller.Products.withIssuance;
import static dgm.catalog.catalog.seller.Products.withPrice;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Способ выдачи товара (R2, Р-184): «по API» отличается от пула признаком {@link Product#sellerApi()}, способ задаётся при создании и не
 * меняется ни в одном статусе, где правка вообще допустима, а отказ называет поле и код {@code immutable}. Отправка на модерацию допустима
 * ровно из тех статусов, что и раньше.
 */
@Tag("TC-330")
@Tag("TC-331")
class ProductApiMethodTest {

    private static final Instant LATER = T0.plusSeconds(3600);

    private static Product api(ProductStatus status) {
        return in(id(1), SELLER, withIssuance(form(), IssuanceMethod.SELLER_API), status, 3, T0);
    }

    private static Product pool(ProductStatus status) {
        return in(status, 3);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Test
    void onlyAnApiProductSaysSellerApi() {
        assertTrue(api(ProductStatus.DRAFT).sellerApi());
        assertFalse(pool(ProductStatus.DRAFT).sellerApi());
    }

    @Test
    void submittableIsExactlyDraftRejectedAndBlockedByTheModerator() {
        for (ProductStatus status : ProductStatus.values()) {
            boolean expected = status == ProductStatus.DRAFT || status == ProductStatus.REJECTED || status == ProductStatus.BLOCKED;
            assertEquals(expected, pool(status).submittable(), status.code());
            assertEquals(expected, api(status).submittable(), status.code());
        }
        assertFalse(sellerBlocked(3).submittable(), "блокировка продавца товар не отправляет");
    }

    @Test
    void anEditThatChangesTheMethodIsAnImmutableViolationInEveryEditableStatus() {
        for (ProductStatus status : new ProductStatus[] {ProductStatus.DRAFT, ProductStatus.REJECTED, ProductStatus.PUBLISHED, ProductStatus.BLOCKED}) {
            ProblemException toApi = assertThrows(ProblemException.class,
                    () -> pool(status).replace(withIssuance(form(), IssuanceMethod.SELLER_API), LATER, REVIEW));
            ProblemException toPool = assertThrows(ProblemException.class,
                    () -> api(status).replace(withIssuance(form(), IssuanceMethod.KEY_POOL), LATER, REVIEW));
            for (ProblemException e : List.of(toApi, toPool)) {
                assertEquals(ProblemType.VALIDATION_FAILED, e.type(), status.code());
                assertEquals(1, errors(e).size());
                assertEquals("/issuanceMethod", errors(e).get(0).get("pointer"));
                assertEquals("immutable", errors(e).get(0).get("code"));
            }
        }
    }

    @Test
    void theStatusIsCheckedBeforeTheMethod() {
        ProblemException e = assertThrows(ProblemException.class,
                () -> pool(ProductStatus.ON_MODERATION).replace(withIssuance(form(), IssuanceMethod.SELLER_API), LATER, REVIEW));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(ProblemType.STATE_CONFLICT, assertThrows(ProblemException.class,
                () -> api(ProductStatus.ARCHIVED).replace(withIssuance(form(), IssuanceMethod.KEY_POOL), LATER, REVIEW)).type());
    }

    @Test
    void anEditThatKeepsTheMethodChangesThePriceWithoutLeavingPublished() {
        Product published = api(ProductStatus.PUBLISHED);
        Product.Edited edited = published.replace(withPrice(published.form(), 129_900L), LATER, REVIEW);
        assertEquals(List.of("price"), edited.changedFields());
        assertEquals(ProductStatus.PUBLISHED, edited.product().status());
        assertEquals(IssuanceMethod.SELLER_API, edited.product().form().issuanceMethod());
        assertEquals(4, edited.product().version());
    }

    @Test
    void anEditWithoutDifferencesStillWritesNothingForAnApiProduct() {
        Product draft = api(ProductStatus.DRAFT);
        assertFalse(draft.replace(draft.form(), LATER, REVIEW).changed());
    }

    @Test
    void theRefusalWithoutAnIntegrationIsA409OfItsOwnType() {
        ProblemException e = Product.integrationRequired();
        assertEquals(ProblemType.INTEGRATION_REQUIRED, e.type());
        assertEquals(409, e.type().status());
        assertEquals("integration-required", e.type().code());
    }
}
