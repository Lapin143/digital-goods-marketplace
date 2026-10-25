package dgm.platform.auditadmin.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.auditadmin.domain.AuditRecordView;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Разбор столбца {@code changes}: контракт описывает значения как строки или пусто, а журнал хранит и числа с логическими значениями (F4-29). */
@Tag("TC-810")
class JdbcAuditReadRepositoryTest {

    @Test
    void numbersAndBooleansBecomeStrings() {
        List<AuditRecordView.Change> changes = JdbcAuditReadRepository.changes("[{\"field\":\"value\",\"personal\":false,\"before\":900,\"after\":1200},"
                + "{\"field\":\"phone_confirmed\",\"personal\":false,\"before\":false,\"after\":true},"
                + "{\"field\":\"ratio\",\"personal\":false,\"before\":1.5,\"after\":null}]");

        assertEquals(3, changes.size());
        assertEquals(new AuditRecordView.Change("value", false, "900", "1200"), changes.get(0));
        assertEquals(new AuditRecordView.Change("phone_confirmed", false, "false", "true"), changes.get(1));
        assertEquals(new AuditRecordView.Change("ratio", false, "1.5", null), changes.get(2));
    }

    @Test
    void personalFieldKeepsItsHashesAndTheFlag() {
        String hash = "a".repeat(64);
        List<AuditRecordView.Change> changes = JdbcAuditReadRepository.changes("[{\"field\":\"phone\",\"personal\":true,\"before\":null,\"after\":\"" + hash + "\"}]");

        assertEquals(1, changes.size());
        assertTrue(changes.get(0).personal());
        assertNull(changes.get(0).before());
        assertEquals(hash, changes.get(0).after());
    }

    @Test
    void emptyOrMissingArrayIsNoChanges() {
        assertTrue(JdbcAuditReadRepository.changes("[]").isEmpty());
        assertTrue(JdbcAuditReadRepository.changes(null).isEmpty());
    }

    @Test
    void textValuesAreNotAltered() {
        List<AuditRecordView.Change> changes = JdbcAuditReadRepository.changes("[{\"field\":\"decisionReason\",\"personal\":false,\"before\":null,"
                + "\"after\":\"Документ не читается\"}]");
        assertEquals("Документ не читается", changes.get(0).after());
    }
}
