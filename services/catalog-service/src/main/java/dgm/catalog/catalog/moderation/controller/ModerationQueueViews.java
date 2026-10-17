package dgm.catalog.catalog.moderation.controller;

import dgm.catalog.catalog.controller.Views;
import dgm.catalog.catalog.domain.ModerationQueueItem;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Тело ответа по схеме {@code ModerationQueuePage} (catalog-service.yaml). */
final class ModerationQueueViews {

    private ModerationQueueViews() {
    }

    static Map<String, Object> queue(ModerationQueuePage page) {
        List<Object> items = new ArrayList<>();
        for (ModerationQueueItem item : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId().toString());
            row.put("sellerId", item.sellerId().toString());
            row.put("title", item.title());
            row.put("productType", item.productType());
            row.put("price", Views.money(item.price(), item.currency()));
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
}
