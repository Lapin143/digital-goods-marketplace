package dgm.catalog.sellers.service;

import java.util.function.Supplier;

/**
 * Транзакция модуля {@code seller_onboarding}. Если вызов пришёл через ключ идемпотентности, действие присоединяется к транзакции
 * охранника того же модуля, и ответ сохраняется вместе с изменением данных.
 */
public interface SellerTransaction {

    <T> T execute(Supplier<T> action);
}
