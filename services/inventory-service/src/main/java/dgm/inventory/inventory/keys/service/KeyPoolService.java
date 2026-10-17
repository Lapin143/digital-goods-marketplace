package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.domain.KeyBatch;
import dgm.inventory.inventory.keys.domain.RejectReason;
import dgm.inventory.inventory.keys.domain.RejectedLine;
import dgm.inventory.inventory.keys.domain.StockInfo;
import dgm.inventory.inventory.keys.domain.UploadReport;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Пул ключей продавца (US-4.2, US-4.3, US-4.5): загрузка списком или файлом и остаток. Правила (ADR-009, уточнения для Ф4):
 *
 * <ul>
 *   <li>товар принадлежит продавцу из токена (claim {@code seller_id}, Р-18), чужой товар не отличается от несуществующего: 404;
 *   <li>выдача из пула и статус товара {@code draft}, {@code on_moderation}, {@code published} или {@code rejected} (U4), иначе 409;
 *   <li>загрузки одного товара идут по очереди: строка копии товара блокируется до конца транзакции, поэтому предел пула не обходится
 *       двумя параллельными запросами, а одно и то же значение принимается ровно один раз;
 *   <li>пул не больше {@value KeyBatch#POOL_LIMIT}: если все годные ключи загрузки не помещаются, загрузка отклоняется целиком (409);
 *   <li>каждое значение шифруется активным ключом данных, дубли находит уникальный индекс {@code (product_id, hmac)};
 *   <li>в той же транзакции пишется {@code stock.changed} с новым остатком.
 * </ul>
 *
 * <p>Значения ключей не попадают ни в журнал, ни в сообщения об ошибках, ни в события.
 */
@Component
public final class KeyPoolService {

    /** Тема, агрегат и версия схемы события остатка (контракт событий). */
    static final String TOPIC = "inventory.events";
    static final String AGGREGATE = "ProductStock";
    static final String STOCK_CHANGED = "stock.changed";
    static final int SCHEMA_VERSION = 1;

    private final ProductCopyRepository products;
    private final KeyRepository keys;
    private final DataKeys dataKeys;
    private final KeyCipher cipher;
    private final KeyHasher hasher;
    private final InventoryTransaction transaction;
    private final Clock clock;

    public KeyPoolService(ProductCopyRepository products, KeyRepository keys, DataKeys dataKeys, KeyCipher cipher, KeyHasher hasher,
                          InventoryTransaction transaction, Clock clock) {
        this.products = products;
        this.keys = keys;
        this.dataKeys = dataKeys;
        this.cipher = cipher;
        this.hasher = hasher;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Загружает ключи в пул товара продавца. Повтор с тем же ключом идемпотентности даёт тот же отчёт (охранник каркаса). */
    public UploadReport upload(UUID sellerId, UUID productId, List<String> lines) {
        return transaction.execute(() -> {
            ProductCopy product = products.lock(productId).filter(p -> p.ownedBy(sellerId)).orElseThrow(KeyPoolService::notFound);
            if (!product.acceptsKeys()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT,
                        "Ключи можно загружать только в товар со способом выдачи «пул» не в статусе «заблокирован» и «архив».",
                        Map.of("currentStatus", product.status()), Map.of());
            }
            KeyBatch.Prepared prepared = KeyBatch.prepare(productId, lines, hasher);
            Map<String, Integer> before = keys.countByStatus(productId);
            if (poolSize(before) + prepared.candidates().size() > KeyBatch.POOL_LIMIT) {
                throw new ProblemException(ProblemType.POOL_LIMIT_EXCEEDED,
                        "В пуле товара не больше " + KeyBatch.POOL_LIMIT + " ключей: загрузка отклонена целиком.");
            }
            List<RejectedLine> rejected = new ArrayList<>(prepared.rejected());
            int accepted = 0;
            if (!prepared.candidates().isEmpty()) {
                DataKeys.Active dek = dataKeys.active();
                List<KeyRepository.NewKey> rows = new ArrayList<>(prepared.candidates().size());
                Map<String, Integer> lineByHmac = new LinkedHashMap<>();
                for (KeyBatch.Candidate candidate : prepared.candidates()) {
                    UUID keyId = Uuids.v7(clock);
                    KeyCipher.Sealed sealed = cipher.seal(dek.dek(), keyId, productId, candidate.value());
                    candidate.value().destroy();
                    rows.add(new KeyRepository.NewKey(keyId, dek.id(), sealed.nonce(), sealed.ciphertext(), candidate.hmac()));
                    lineByHmac.put(KeyHasher.hex(candidate.hmac()), candidate.line());
                }
                Set<String> inserted = keys.insertNew(productId, rows);
                accepted = inserted.size();
                for (Map.Entry<String, Integer> entry : lineByHmac.entrySet()) {
                    if (!inserted.contains(entry.getKey())) {
                        rejected.add(new RejectedLine(entry.getValue(), RejectReason.DUPLICATE_IN_POOL));
                    }
                }
            }
            int available = keys.countByStatus(productId).getOrDefault("free", 0);
            if (accepted > 0) {
                publishStock(productId, available);
            }
            rejected.sort((a, b) -> Integer.compare(a.line(), b.line()));
            List<RejectedLine> shown = rejected.size() > UploadReport.MAX_REJECTED ? List.copyOf(rejected.subList(0, UploadReport.MAX_REJECTED))
                    : List.copyOf(rejected);
            return new UploadReport(accepted, rejected.size(), shown, available, KeyBatch.POOL_LIMIT);
        });
    }

    /** Остаток своего товара: свободные, зарезервированные и выданные ключи (US-4.5). */
    public StockInfo stock(UUID sellerId, UUID productId) {
        products.find(productId).filter(p -> p.ownedBy(sellerId)).orElseThrow(KeyPoolService::notFound);
        Map<String, Integer> counts = keys.countByStatus(productId);
        return new StockInfo(productId, counts.getOrDefault("free", 0), counts.getOrDefault("reserved", 0), counts.getOrDefault("issued", 0),
                KeyBatch.POOL_LIMIT, clock.instant());
    }

    /**
     * {@code stock.changed}: схема требует версию остатка, растущую при каждом изменении, а столбца для неё в базе нет. Версией служит
     * момент изменения в миллисекундах: загрузки одного товара идут по очереди под блокировкой его строки, а потребитель упорядочивает
     * события по метке времени конверта (Р-84).
     */
    private void publishStock(UUID productId, int available) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId.toString());
        data.put("available", available);
        data.put("version", clock.millis());
        transaction.publish(TOPIC, AGGREGATE, productId.toString(), STOCK_CHANGED, SCHEMA_VERSION, data);
    }

    private static int poolSize(Map<String, Integer> counts) {
        return counts.getOrDefault("free", 0) + counts.getOrDefault("reserved", 0) + counts.getOrDefault("issued", 0);
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Товар не найден.");
    }
}
