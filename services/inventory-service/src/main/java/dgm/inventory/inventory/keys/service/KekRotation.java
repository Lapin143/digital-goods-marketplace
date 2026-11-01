package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.KekRing;
import dgm.inventory.inventory.keys.repository.DataKeyRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Ротация мастер-ключа (KEK) без остановки сервиса и без перешифровки значений (ADR-009, Р-174): каждый DEK разворачивается текущим
 * мастер-ключом и заворачивается следующим, версия мастер-ключа в строке растёт на единицу. Значения ключей зашифрованы DEK и остаются как
 * были, поэтому перешифровывается десяток строк, а не миллионы.
 *
 * <p>Всё идёт одной транзакцией под консультативной блокировкой: либо все DEK переведены на следующий мастер-ключ, либо ни один, второй
 * экземпляр сервиса ничего не делает. Повторный запуск ничего не находит и возвращает ноль (идемпотентность). Открытые DEK и мастер-ключи
 * в журнал не попадают, в результат только числа.
 */
@Component
public final class KekRotation {

    private final DataKeyRepository repository;
    private final KekRing ring;
    private final InventoryTransaction transaction;

    public KekRotation(DataKeyRepository repository, KekRing ring, InventoryTransaction transaction) {
        this.repository = repository;
        this.ring = ring;
        this.transaction = transaction;
    }

    /**
     * Результат ротации.
     *
     * @param fromVersion прежняя версия мастер-ключа
     * @param toVersion   следующая версия
     * @param rewrapped   сколько DEK перезавёрнуто
     * @param skipped     {@code true}, если блокировку держит другой экземпляр и этот ничего не делал
     */
    public record Result(int fromVersion, int toVersion, int rewrapped, boolean skipped) {
    }

    /**
     * Переводит все DEK на следующий мастер-ключ.
     *
     * @throws IllegalStateException следующий мастер-ключ не задан
     */
    public Result rotate() {
        if (!ring.rotating()) {
            throw new IllegalStateException("Следующий мастер-ключ не задан: ротации нечем выполняться");
        }
        int from = ring.currentVersion();
        int to = ring.nextVersion();
        return transaction.execute(() -> {
            if (!repository.tryLockRotation()) {
                return new Result(from, to, 0, true);
            }
            List<DataKeyRepository.Stored> old = repository.lockByKekVersion(from);
            for (DataKeyRepository.Stored stored : old) {
                byte[] dek = ring.current().unwrap(stored.wrapped());
                try {
                    repository.rewrap(stored.id(), ring.next().wrap(dek), to);
                } finally {
                    Arrays.fill(dek, (byte) 0);
                }
            }
            return new Result(from, to, old.size(), false);
        });
    }
}
