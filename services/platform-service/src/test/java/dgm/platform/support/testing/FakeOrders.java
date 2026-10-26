package dgm.platform.support.testing;

import dgm.kit.client.DependencyUnavailableException;
import dgm.platform.support.client.OrderClient;
import dgm.platform.support.client.OrderRefusedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code order-service} в памяти: помнит каждый вызов и действует по ключу идемпотентности, как настоящий. Первый вызов с ключом считается
 * действием ({@link #effects()}, одно событие {@code order.address-updated}), повтор с тем же ключом действия не даёт.
 */
public final class FakeOrders implements OrderClient {

    /** Один вызов. */
    public record Call(UUID orderId, UUID ticketId, String email, UUID key) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Set<UUID> executed = new HashSet<>();
    private RuntimeException failure;
    /** Сбой после действия: ответ потерян, а событие уже опубликовано (тайм-аут после записи). */
    private boolean failAfterEffect;

    @Override
    public void updateDeliveryAddress(UUID orderId, UUID ticketId, String email, UUID idempotencyKey) {
        calls.add(new Call(orderId, ticketId, email, idempotencyKey));
        if (failure != null && !failAfterEffect) {
            throw failure;
        }
        executed.add(idempotencyKey);
        if (failure != null) {
            throw failure;
        }
    }

    /** Сосед недоступен до обработки запроса. */
    public void unavailable() {
        failure = new DependencyUnavailableException("order-service", DependencyUnavailableException.TIMEOUT, 0, null);
        failAfterEffect = false;
    }

    /** Сосед обработал запрос, но ответ потерян. */
    public void unavailableAfterEffect() {
        failure = new DependencyUnavailableException("order-service", DependencyUnavailableException.TIMEOUT, 0, null);
        failAfterEffect = true;
    }

    public void refuse(int status, String problemCode) {
        failure = new OrderRefusedException(status, problemCode);
        failAfterEffect = false;
    }

    public void recover() {
        failure = null;
        failAfterEffect = false;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Сколько раз заказ действительно сменил адрес (разные ключи). */
    public int effects() {
        return executed.size();
    }
}
