package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.ReviewQueueItem;
import dgm.catalog.sellers.domain.ReviewQueuePage;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.service.SellerModerationService.Card;
import dgm.catalog.sellers.service.SellerModerationService.CardDocument;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Тела ответов модератора по схемам {@code SellerApplicationQueuePage}, {@code SellerApplicationQueueItem}, {@code SellerApplicationCard}. */
final class ModerationViews {

    private ModerationViews() {
    }

    static Map<String, Object> queue(ReviewQueuePage page) {
        List<Object> items = new ArrayList<>();
        for (ReviewQueueItem item : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sellerId", item.sellerId().toString());
            row.put("name", item.name());
            row.put("sellerType", item.sellerType().code());
            row.put("documentsCount", item.documentsCount());
            row.put("submittedAt", Timestamps.format(item.submittedAt()));
            row.put("reviewDeadlineAt", Timestamps.format(item.reviewDeadlineAt()));
            row.put("overdue", item.overdue(page.at()));
            items.add(row);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("p", n.submittedAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    static Map<String, Object> card(Card card) {
        SellerProfile p = card.profile();
        SellerForm f = p.form();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerId", p.id().toString());
        body.put("userId", p.userId().toString());
        body.put("status", p.status().code());
        body.put("sellerType", f.sellerType().code());
        body.put("name", f.name());
        Map<String, Object> payout = new LinkedHashMap<>();
        payout.put("recipientName", f.payoutRecipientName());
        payout.put("bankName", f.payoutBankName());
        payout.put("bik", f.payoutBik());
        payout.put("accountNumber", f.payoutAccountNumber());
        body.put("payoutDetails", payout);
        body.put("assortmentDescription", f.assortmentDescription());
        List<Object> documents = new ArrayList<>();
        for (CardDocument item : card.documents()) {
            Map<String, Object> document = SellerViews.document(item.document());
            document.put("link", SellerViews.link(item.link()));
            documents.add(document);
        }
        body.put("documents", documents);
        body.put("submittedAt", Timestamps.format(p.submittedAt()));
        body.put("reviewDeadlineAt", Timestamps.format(p.reviewDeadlineAt()));
        return body;
    }
}
