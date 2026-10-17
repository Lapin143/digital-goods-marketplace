package dgm.catalog.sellers.domain;

/**
 * Нарушение правила по полю тела запроса: указатель JSON Pointer, код из conventions.md (раздел 9.2) и пояснение для человека.
 */
public record FieldViolation(String pointer, String code, String detail) {
}
