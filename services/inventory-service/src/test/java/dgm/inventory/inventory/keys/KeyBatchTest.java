package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.domain.KeyBatch;
import dgm.inventory.inventory.keys.domain.RejectReason;
import dgm.inventory.inventory.keys.domain.RejectedLine;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Разбор загружаемого списка (US-4.2, US-4.3, Р-12): номера строк и причины отказа. */
class KeyBatchTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private final KeyHasher hasher = new KeyHasher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    private KeyBatch.Prepared prepare(String... lines) {
        return KeyBatch.prepare(PRODUCT, Arrays.asList(lines), hasher);
    }

    @Test
    void validDistinctKeysAreAllCandidatesInLineOrder() {
        KeyBatch.Prepared prepared = prepare("A-1", "A-2", "A-3");
        assertEquals(List.of(1, 2, 3), prepared.candidates().stream().map(KeyBatch.Candidate::line).toList());
        assertEquals(List.of(), prepared.rejected());
    }

    @Test
    void emptyAndBlankLinesAreRejectedWithTheirNumbers() {
        KeyBatch.Prepared prepared = prepare("A-1", "", "  \r", null, "A-2");
        assertEquals(List.of(1, 5), prepared.candidates().stream().map(KeyBatch.Candidate::line).toList());
        assertEquals(List.of(new RejectedLine(2, RejectReason.EMPTY), new RejectedLine(3, RejectReason.EMPTY), new RejectedLine(4, RejectReason.EMPTY)),
                prepared.rejected());
    }

    @Test
    void aKeyLongerThanAKilobyteIsTooLongAndExactlyAKilobyteIsFine() {
        KeyBatch.Prepared prepared = prepare("x".repeat(1024), "x".repeat(1025));
        assertEquals(1, prepared.candidates().size());
        assertEquals(List.of(new RejectedLine(2, RejectReason.TOO_LONG)), prepared.rejected());
    }

    @Test
    void theLengthIsCountedAfterTrimming() {
        assertEquals(1, prepare("  " + "x".repeat(1024) + "  ").candidates().size());
    }

    @Test
    void aRepeatInTheUploadIsRejectedAndTheFirstOccurrenceStays() {
        KeyBatch.Prepared prepared = prepare("A-1", "A-2", " A-1 ", "A-2\n", "A-3");
        assertEquals(List.of(1, 2, 5), prepared.candidates().stream().map(KeyBatch.Candidate::line).toList());
        assertEquals(List.of(new RejectedLine(3, RejectReason.DUPLICATE_IN_UPLOAD), new RejectedLine(4, RejectReason.DUPLICATE_IN_UPLOAD)),
                prepared.rejected());
    }

    @Test
    void theCaseOfAKeyMatters() {
        assertEquals(2, prepare("abc", "ABC").candidates().size());
    }

    @Test
    void candidatesCarryTheHmacAndNeverTheValueInText() {
        KeyBatch.Candidate candidate = prepare("SECRET-KEY-VALUE").candidates().get(0);
        assertEquals(32, candidate.hmac().length);
        assertFalse(candidate.value().toString().contains("SECRET"));
    }
}
