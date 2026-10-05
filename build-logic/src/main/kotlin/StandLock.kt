import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Замок стенда. Интеграционные тесты разных проектов работают с одними и теми же базами и темами Kafka поднятого стенда
 * (например, публикатор Outbox теста каркаса и публикатор сервиса заказов читают одну таблицу `outbox` базы `order_db`),
 * поэтому две такие задачи одновременно не запускаются, хотя `org.gradle.parallel=true`. Службе нечего делать: важно только
 * ограничение `maxParallelUsages = 1`, которое задаёт плагин `dgm.java-conventions`.
 */
abstract class StandLock : BuildService<BuildServiceParameters.None>
