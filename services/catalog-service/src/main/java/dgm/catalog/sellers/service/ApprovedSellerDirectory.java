package dgm.catalog.sellers.service;

import dgm.catalog.sellers.api.ApprovedSellers;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.repository.SellerRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link ApprovedSellers} поверх репозитория профилей: одно чтение по первичному ключу, без блокировки. */
@Component
public final class ApprovedSellerDirectory implements ApprovedSellers {

    private final SellerRepository repository;

    public ApprovedSellerDirectory(SellerRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isApproved(UUID sellerId) {
        return repository.findById(sellerId).map(profile -> profile.status() == SellerStatus.APPROVED).orElse(false);
    }
}
