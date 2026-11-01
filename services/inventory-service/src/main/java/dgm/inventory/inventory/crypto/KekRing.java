package dgm.inventory.inventory.crypto;

/**
 * Мастер-ключи (KEK), которые сервис знает сейчас (ADR-009): текущий и, пока идёт ротация, следующий. Версия мастер-ключа записана в
 * {@code inventory.data_key.kek_version} у каждого DEK; по ней выбирается, чем DEK открывать. Новые DEK всегда заворачиваются самым
 * новым из известных мастер-ключей, поэтому во время ротации не появляется ключей данных, которые пришлось бы перешифровывать ещё раз.
 *
 * <p>Ротация идёт в три шага оператора ({@code runbook.md}): положить следующий мастер-ключ рядом с текущим и запустить перешифровку,
 * убедиться, что ключей данных со старой версией не осталось, сделать следующий мастер-ключ текущим. Значения ключей при этом не
 * перешифровываются: меняется только обёртка нескольких DEK.
 */
public final class KekRing {

    private final KeyCipher current;
    private final int currentVersion;
    private final KeyCipher next;

    /**
     * @param current        текущий мастер-ключ
     * @param currentVersion его версия, не меньше единицы
     * @param next           следующий мастер-ключ (версия на единицу больше) или {@code null}, если ротации нет
     */
    public KekRing(KeyCipher current, int currentVersion, KeyCipher next) {
        if (currentVersion < 1) {
            throw new IllegalArgumentException("Версия мастер-ключа не меньше 1");
        }
        this.current = current;
        this.currentVersion = currentVersion;
        this.next = next;
    }

    /** Кольцо с одним мастер-ключом первой версии: так работает R1 без ротации. */
    public static KekRing single(KeyCipher cipher) {
        return new KekRing(cipher, 1, null);
    }

    public KeyCipher current() {
        return current;
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** Идёт ли ротация: следующий мастер-ключ положен рядом с текущим. */
    public boolean rotating() {
        return next != null;
    }

    /** Следующий мастер-ключ. @throws IllegalStateException ротации нет */
    public KeyCipher next() {
        if (next == null) {
            throw new IllegalStateException("Следующий мастер-ключ не задан");
        }
        return next;
    }

    public int nextVersion() {
        return currentVersion + 1;
    }

    /** Самый новый из известных мастер-ключей: им заворачиваются новые DEK. */
    public KeyCipher newest() {
        return next != null ? next : current;
    }

    public int newestVersion() {
        return next != null ? nextVersion() : currentVersion;
    }

    /** Знает ли сервис мастер-ключ этой версии. */
    public boolean knows(int kekVersion) {
        return kekVersion == currentVersion || (next != null && kekVersion == nextVersion());
    }

    /**
     * Мастер-ключ нужной версии.
     *
     * @throws CryptoException если такой версии в секретах сервиса нет (ключ данных зашифрован мастер-ключом, которого сервис не получил)
     */
    public KeyCipher forVersion(int kekVersion) {
        if (kekVersion == currentVersion) {
            return current;
        }
        if (next != null && kekVersion == nextVersion()) {
            return next;
        }
        throw new CryptoException("Мастер-ключа версии " + kekVersion + " нет в секретах сервиса");
    }
}
