package dgm.gateway;

import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * Память шлюза о проверенных ключах (ADR-027, п. 3). Ключ записи это SHA-256 значения ключа, самого значения здесь нет. Хранятся только
 * окончательные итоги: ключ принят или отклонён (неизвестный, отозванный, продавец не одобрен). Итог «каталог не ответил» не хранится.
 */
public interface ApiKeyCache {

    /** Итог по хешу; пустой поток значит «в памяти нет». Ошибка в потоке значит «память недоступна»: вызывающий идёт к каталогу. */
    Mono<ApiKeyVerifier.Verdict> get(String hash);

    /** Запоминает итог на срок {@code ttl}; ошибка в потоке не мешает ответу клиенту. */
    Mono<Void> put(String hash, ApiKeyVerifier.Verdict verdict, Duration ttl);
}
