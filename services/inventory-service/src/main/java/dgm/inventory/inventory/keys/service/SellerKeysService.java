package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.domain.KeyBatch;
import dgm.inventory.inventory.keys.domain.RejectReason;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.repository.ReservationRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ключи продавца для заказа с выдачей по API ({@code storeSellerKeys}, OP-99, ADR-028, Р-190). Продавец прислал значения в ответ на запрос выдачи,
 * {@code delivery-service} сразу передаёт их сюда: значения шифруются как обычные (активный ключ данных, HMAC по товару, INV-03), привязываются к заказу
 * и его резерву и проходят статусы {@code free}, {@code reserved}, {@code issued} (SM-03) одной транзакцией. Резерв к этому моменту {@code used}: оплату
 * подтвердило {@code confirmReservation}, ключей он не привязывал (Р-183).
 *
 * <p>Правила:
 *
 * <ul>
 *   <li>резерв заказа блокируется на время записи, поэтому его не снимут (SM-08/T5) посреди приёма ключей;
 *   <li>у заказа нет резерва или товар не по API: 404; резерв не {@code used} (не подтверждён или снят после отмены заказа): 409 (U23);
 *   <li>число значений равно числу единиц в заказе, значения не пусты, не длиннее 1024 знаков и различны между собой, иначе 422;
 *   <li>значение, которое по этому товару уже есть (в том числе выдано по другому заказу), отклоняется как дубликат (INV-03): 422, ничего не записано;
 *   <li>повтор тех же значений по тому же заказу ничего не меняет и даёт тот же итог; другие значения по заказу, у которого ключи уже есть, это 409.
 * </ul>
 *
 * <p>Значения не попадают ни в журнал, ни в сообщения об ошибках, ни в события: сообщения о нарушениях называют только позицию в списке.
 */
@Component
public final class SellerKeysService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerKeysService.class);

    /** Итог приёма: сколько ключей записано. Значений нет. */
    public record Stored(UUID orderId, int stored) {
    }

    private final ReservationRepository reservations;
    private final ProductCopyRepository products;
    private final KeyRepository keys;
    private final DataKeys dataKeys;
    private final KeyCipher cipher;
    private final KeyHasher hasher;
    private final InventoryTransaction transaction;
    private final Clock clock;

    public SellerKeysService(ReservationRepository reservations, ProductCopyRepository products, KeyRepository keys, DataKeys dataKeys, KeyCipher cipher,
                             KeyHasher hasher, InventoryTransaction transaction, Clock clock) {
        this.reservations = reservations;
        this.products = products;
        this.keys = keys;
        this.dataKeys = dataKeys;
        this.cipher = cipher;
        this.hasher = hasher;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Принимает ключи продавца по заказу.
     *
     * @param requestId запрос выдачи, на который ответил продавец: только для журнала
     * @param values    значения ключей в порядке ответа продавца
     */
    public Stored store(UUID orderId, UUID requestId, List<String> values) {
        Stored stored = transaction.execute(() -> {
            Reservation reservation = reservations.lockLatestByOrder(orderId).orElseThrow(SellerKeysService::notFound);
            products.find(reservation.productId()).filter(p -> p.sellerApi()).orElseThrow(SellerKeysService::notFound);
            if (!reservation.isUsed()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Оплата заказа не подтверждена или резерв снят: ключи продавца принять нельзя.",
                        Map.of("currentStatus", reservation.status()), Map.of());
            }
            if (values.size() != reservation.quantity()) {
                throw invalid("/keys", "out_of_range", "Ключей должно быть ровно " + reservation.quantity() + " по числу единиц в заказе.");
            }
            KeyBatch.Prepared prepared = KeyBatch.prepare(reservation.productId(), values, hasher);
            try {
                if (!prepared.rejected().isEmpty()) {
                    List<Map<String, Object>> errors = new ArrayList<>();
                    prepared.rejected().forEach(line -> errors.add(error("/keys/" + (line.line() - 1), code(line.reason()), "Значение не принято.")));
                    throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                            Map.of("errors", errors), Map.of());
                }
                Set<String> incoming = new HashSet<>();
                prepared.candidates().forEach(c -> incoming.add(KeyHasher.hex(c.hmac())));
                Set<String> existing = keys.hmacsByReservation(reservation.id());
                if (!existing.isEmpty()) {
                    if (existing.equals(incoming)) {
                        return new Stored(orderId, existing.size());
                    }
                    throw new ProblemException(ProblemType.STATE_CONFLICT, "Ключи по этому заказу уже приняты, другие значения не принимаются.",
                            Map.of("currentStatus", "issued"), Map.of());
                }
                return insert(orderId, reservation, prepared);
            } finally {
                prepared.candidates().forEach(c -> c.value().destroy());
            }
        });
        LOG.info("Ключи продавца по запросу {} приняты: заказ {}, штук {}", requestId, orderId, stored.stored());
        return stored;
    }

    private Stored insert(UUID orderId, Reservation reservation, KeyBatch.Prepared prepared) {
        DataKeys.Active dek = dataKeys.active();
        List<KeyRepository.NewKey> rows = new ArrayList<>(prepared.candidates().size());
        List<UUID> ids = new ArrayList<>(prepared.candidates().size());
        Map<String, Integer> lineByHmac = new LinkedHashMap<>();
        for (KeyBatch.Candidate candidate : prepared.candidates()) {
            UUID keyId = Uuids.v7(clock);
            KeyCipher.Sealed sealed = cipher.seal(dek.dek(), keyId, reservation.productId(), candidate.value());
            rows.add(new KeyRepository.NewKey(keyId, dek.id(), sealed.nonce(), sealed.ciphertext(), candidate.hmac()));
            ids.add(keyId);
            lineByHmac.put(KeyHasher.hex(candidate.hmac()), candidate.line());
        }
        Set<String> inserted = keys.insertNew(reservation.productId(), rows);
        if (inserted.size() != rows.size()) {
            // значение уже есть в пуле товара (INV-03): ничего не записываем, транзакция откатывается вместе со вставленными
            List<Map<String, Object>> errors = new ArrayList<>();
            lineByHmac.forEach((hmac, line) -> {
                if (!inserted.contains(hmac)) {
                    errors.add(error("/keys/" + (line - 1), "not_unique", "Такое значение по этому товару уже есть."));
                }
            });
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.", Map.of("errors", errors),
                    Map.of());
        }
        int bound = keys.reserveByIds(ids, orderId, reservation.id());
        int issued = keys.issueByReservation(reservation.id(), clock.instant());
        if (bound != rows.size() || issued != rows.size()) {
            // нарушение согласованности: привязано или выдано не столько, сколько вставлено; откат
            throw new IllegalStateException("Ключи резерва " + reservation.id() + ": вставлено " + rows.size() + ", привязано " + bound + ", выдано " + issued);
        }
        return new Stored(orderId, rows.size());
    }

    private static String code(RejectReason reason) {
        return switch (reason) {
            case EMPTY -> "invalid_format";
            case TOO_LONG -> "too_long";
            case DUPLICATE_IN_UPLOAD, DUPLICATE_IN_POOL -> "not_unique";
        };
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }

    private static ProblemException invalid(String pointer, String code, String detail) {
        return new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                Map.of("errors", List.of(error(pointer, code, detail))), Map.of());
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Для заказа нет резерва товара с выдачей по API.");
    }
}
