package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.kit.storage.ObjectStorage;
import dgm.kit.time.Timestamps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Тела ответов по схемам {@code SellerApplication}, {@code SellerDocument}, {@code DocumentLink} (catalog-service.yaml). */
final class SellerViews {

    private SellerViews() {
    }

    /** ETag анкеты: версия в кавычках (conventions.md, раздел 8). Документы в версию не входят, их загрузка анкету не меняет (Р-60). */
    static String etag(SellerProfile profile) {
        return "\"" + profile.version() + "\"";
    }

    static Map<String, Object> application(Application application, SellerSettings settings) {
        SellerProfile p = application.profile();
        SellerForm f = p.form();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerId", p.id().toString());
        body.put("status", p.status().code());
        if (f.sellerType() != null) {
            body.put("sellerType", f.sellerType().code());
        }
        if (f.name() != null) {
            body.put("name", f.name());
        }
        if (f.hasPayoutDetails()) {
            Map<String, Object> payout = new LinkedHashMap<>();
            put(payout, "recipientName", f.payoutRecipientName());
            put(payout, "bankName", f.payoutBankName());
            put(payout, "bik", f.payoutBik());
            put(payout, "accountNumber", f.payoutAccountNumber());
            body.put("payoutDetails", payout);
        }
        if (f.assortmentDescription() != null) {
            body.put("assortmentDescription", f.assortmentDescription());
        }
        List<Object> documents = new ArrayList<>();
        for (SellerDocument document : application.documents()) {
            documents.add(document(document));
        }
        body.put("documents", documents);
        body.put("decisionReason", p.decisionReason());
        Optional<Instant> rejectedAt = p.shownRejectedAt();
        body.put("rejectedAt", time(rejectedAt.orElse(null)));
        body.put("nextApplicationAt", time(rejectedAt.flatMap(at -> p.nextApplicationAt(settings.reapplicationPause())).orElse(null)));
        body.put("submittedAt", time(p.submittedAt()));
        body.put("reviewDeadlineAt", time(p.status() == SellerStatus.ON_REVIEW ? p.reviewDeadlineAt() : null));
        body.put("createdAt", time(p.createdAt()));
        body.put("updatedAt", time(p.updatedAt()));
        return body;
    }

    static Map<String, Object> document(SellerDocument d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", d.id().toString());
        body.put("fileName", d.fileName());
        body.put("contentType", d.type().contentType());
        body.put("sizeBytes", d.sizeBytes());
        body.put("uploadedAt", Timestamps.format(d.uploadedAt()));
        return body;
    }

    static Map<String, Object> link(ObjectStorage.PresignedLink link) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", link.url().toString());
        body.put("expiresAt", Timestamps.format(link.expiresAt()));
        return body;
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String time(Instant instant) {
        return instant == null ? null : Timestamps.format(instant);
    }
}
