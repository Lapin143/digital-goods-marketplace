package dgm.catalog.sellers.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Интерфейс {@code ApprovedSellers} модуля sellers для условия U3: «одобрен» значит только статус approved. */
class ApprovedSellerDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private static SellerProfile profileIn(FakeSellers sellers, SellerStatus status) {
        SellerProfile profile = FakeSellers.profile(FakeSellers.USER, status, FakeSellers.completeForm(), NOW);
        sellers.put(profile);
        return profile;
    }

    @Test
    void anApprovedProfileIsApproved() {
        FakeSellers sellers = new FakeSellers();
        SellerProfile profile = profileIn(sellers, SellerStatus.APPROVED);
        assertTrue(new ApprovedSellerDirectory(sellers).isApproved(profile.id()));
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStatusIsNotApproved(SellerStatus status) {
        FakeSellers sellers = new FakeSellers();
        SellerProfile profile = profileIn(sellers, status);
        assertFalse(new ApprovedSellerDirectory(sellers).isApproved(profile.id()));
    }

    @Test
    void anUnknownProfileIsNotApproved() {
        assertFalse(new ApprovedSellerDirectory(new FakeSellers()).isApproved(UUID.fromString("0199e0a0-0000-7000-8000-000000000999")));
    }
}
