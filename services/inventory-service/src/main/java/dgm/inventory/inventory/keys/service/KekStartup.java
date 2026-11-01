package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.KekRing;
import dgm.inventory.inventory.keys.repository.DataKeyRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Проверки мастер-ключа при старте и ротация по команде оператора (ADR-009, Р-174).
 *
 * <ol>
 *   <li><b>Каждый DEK открывается.</b> Для каждой строки {@code data_key} в секретах должен быть мастер-ключ её версии. Иначе сервис не
 *       запускается: лучше остановка при выкладке, чем отказ первой же выдачи ключа. Так оператор, который заменил мастер-ключ до
 *       завершения ротации, узнаёт об этом сразу.
 *   <li><b>Ротация.</b> Если оператор положил рядом {@code inventory_kek_next} и включил {@code dgm.inventory.kek.rotate}, сервис
 *       перезаворачивает DEK и пишет число в журнал и в счётчик {@code kek_rotation_rewrapped_total}.
 * </ol>
 */
@Component
public final class KekStartup implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(KekStartup.class);

    private final DataKeyRepository repository;
    private final KekRing ring;
    private final KekRotation rotation;
    private final InventoryTransaction transaction;
    private final boolean rotate;
    private final Counter rewrapped;

    public KekStartup(DataKeyRepository repository, KekRing ring, KekRotation rotation, InventoryTransaction transaction,
            MeterRegistry meters, @Value("${dgm.inventory.kek.rotate:false}") boolean rotate) {
        this.repository = repository;
        this.ring = ring;
        this.rotation = rotation;
        this.transaction = transaction;
        this.rotate = rotate;
        this.rewrapped = Counter.builder("kek_rotation_rewrapped_total")
                .description("Сколько ключей данных перезавёрнуто следующим мастер-ключом при ротации KEK").register(meters);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rotate) {
            if (!ring.rotating()) {
                throw new IllegalStateException("Включена ротация мастер-ключа, но секрета inventory_kek_next нет");
            }
            KekRotation.Result result = rotation.rotate();
            rewrapped.increment(result.rewrapped());
            LOG.info("Ротация мастер-ключа: версия {} -> {}, перезавёрнуто ключей данных {}{}", result.fromVersion(), result.toVersion(),
                    result.rewrapped(), result.skipped() ? " (блокировку держит другой экземпляр)" : "");
        }
        List<DataKeyRepository.Stored> all = transaction.execute(repository::all);
        for (DataKeyRepository.Stored stored : all) {
            if (!ring.knows(stored.kekVersion())) {
                throw new IllegalStateException("Ключ данных " + stored.id() + " зашифрован мастер-ключом версии " + stored.kekVersion()
                        + ", а в секретах сервиса версии " + ring.currentVersion() + (ring.rotating() ? " и " + ring.nextVersion() : "")
                        + ": ротация не завершена или секрет inventory_kek заменён раньше времени");
            }
        }
        long pending = all.stream().filter(s -> s.kekVersion() == ring.currentVersion()).count();
        if (ring.rotating()) {
            LOG.info("Мастер-ключ: текущая версия {}, следующая {}, ключей данных на текущей версии {}", ring.currentVersion(),
                    ring.nextVersion(), pending);
        }
    }
}
