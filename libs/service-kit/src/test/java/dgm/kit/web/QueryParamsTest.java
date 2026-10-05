package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор параметров списка: значения по умолчанию, границы, копление ошибок и ответ 422 (conventions.md, раздел 10). */
class QueryParamsTest {

    private static MockHttpServletRequest request(String... nameValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        for (int i = 0; i < nameValue.length; i += 2) {
            request.addParameter(nameValue[i], nameValue[i + 1]);
        }
        return request;
    }

    /** Ошибки в виде «параметр:код» в порядке появления. */
    private static List<String> errors(ProblemException e) {
        List<?> list = (List<?>) e.extensions().get("errors");
        return list.stream().map(o -> ((Map<?, ?>) o).get("parameter") + ":" + ((Map<?, ?>) o).get("code")).toList();
    }

    private static List<String> failures(QueryParams params) {
        ProblemException e = assertThrows(ProblemException.class, params::check);
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        return errors(e);
    }

    @Test
    void noParametersGiveDefaults() {
        QueryParams params = QueryParams.of(request(), "q");
        assertEquals(20, params.limit());
        assertTrue(params.cursor().isEmpty());
        assertTrue(params.valid());
        params.check();
    }

    @Test
    void limitAcceptsBoundaries() {
        assertEquals(1, QueryParams.of(request("limit", "1")).limit());
        assertEquals(100, QueryParams.of(request("limit", "100")).limit());
    }

    @Test
    void limitOutOfRangeIsReported() {
        assertEquals(List.of("limit:out_of_range"), failures(paramsAfterLimit("0")));
        assertEquals(List.of("limit:out_of_range"), failures(paramsAfterLimit("101")));
    }

    private static QueryParams paramsAfterLimit(String value) {
        QueryParams params = QueryParams.of(request("limit", value));
        assertEquals(20, params.limit(), "при ошибке берётся значение по умолчанию, ответ всё равно 422");
        return params;
    }

    @Test
    void limitMustBeNonNegativeInteger() {
        for (String bad : List.of("abc", "-1", "1.5", "", " 5", "0x10", "99999999999999999999")) {
            assertEquals(List.of("limit:invalid_format"), failures(paramsAfterLimit(bad)), bad);
        }
    }

    @Test
    void unknownParametersAreErrorsInAlphabeticalOrder() {
        QueryParams params = QueryParams.of(request("zeta", "1", "alpha", "2", "q", "ok"), "q");
        assertEquals(List.of("alpha:unknown_value", "zeta:unknown_value"), failures(params));
    }

    @Test
    void limitAndCursorAreAlwaysKnown() {
        assertTrue(QueryParams.of(request("limit", "5", "cursor", "abc")).valid());
    }

    @Test
    void repeatedParameterIsInvalid() {
        QueryParams params = QueryParams.of(request("q", "a", "q", "b"), "q");
        assertTrue(params.text("q", 1, 10).isEmpty());
        assertEquals(List.of("q:invalid_format"), failures(params));
    }

    @Test
    void textLengthIsChecked() {
        QueryParams tooShort = QueryParams.of(request("q", ""), "q");
        assertTrue(tooShort.text("q", 2, 5).isEmpty());
        assertEquals(List.of("q:too_short"), failures(tooShort));

        QueryParams tooLong = QueryParams.of(request("q", "abcdef"), "q");
        assertTrue(tooLong.text("q", 2, 5).isEmpty());
        assertEquals(List.of("q:too_long"), failures(tooLong));

        assertEquals(Optional.of("abcde"), QueryParams.of(request("q", "abcde"), "q").text("q", 2, 5));
    }

    @Test
    void textIsOptional() {
        assertTrue(QueryParams.of(request(), "q").text("q", 1, 5).isEmpty());
    }

    @Test
    void matchingChecksFormat() {
        java.util.regex.Pattern country = java.util.regex.Pattern.compile("^[A-Z]{2}$");
        assertEquals(Optional.of("RU"), QueryParams.of(request("c", "RU"), "c").matching("c", country, 2));
        QueryParams bad = QueryParams.of(request("c", "ru"), "c");
        assertTrue(bad.matching("c", country, 2).isEmpty());
        assertEquals(List.of("c:invalid_format"), failures(bad));
    }

    @Test
    void integerRange() {
        assertEquals(500, QueryParams.of(request("p", "500"), "p").integer("p", 0, 1000).getAsLong());
        QueryParams outside = QueryParams.of(request("p", "1001"), "p");
        assertTrue(outside.integer("p", 0, 1000).isEmpty());
        assertEquals(List.of("p:out_of_range"), failures(outside));
    }

    @Test
    void instantAcceptsAnyOffset() {
        QueryParams params = QueryParams.of(request("from", "2026-10-03T15:00:00+03:00"), "from");
        assertEquals(Optional.of(Instant.parse("2026-10-03T12:00:00Z")), params.instant("from"));
    }

    @Test
    void instantRejectsOtherFormats() {
        for (String bad : List.of("2026-10-03", "yesterday", "2026-10-03T12:00:00")) {
            QueryParams params = QueryParams.of(request("from", bad), "from");
            assertTrue(params.instant("from").isEmpty(), bad);
            assertEquals(List.of("from:invalid_format"), failures(params), bad);
        }
    }

    @Test
    void listKeepsOrderAndDropsRepeats() {
        QueryParams params = QueryParams.of(request("status", "paid,issued,paid"), "status");
        assertEquals(Optional.of(List.of("paid", "issued")), params.list("status", Set.of("paid", "issued", "cancelled")));
        assertTrue(params.valid());
    }

    @Test
    void listRejectsUnknownAndMalformedValues() {
        QueryParams unknown = QueryParams.of(request("status", "paid,lost"), "status");
        assertTrue(unknown.list("status", Set.of("paid")).isEmpty());
        assertEquals(List.of("status:unknown_value"), failures(unknown));

        for (String bad : List.of("Paid", "paid,,issued", "paid;issued", ",")) {
            QueryParams malformed = QueryParams.of(request("status", bad), "status");
            assertTrue(malformed.list("status", Set.of("paid", "issued")).isEmpty(), bad);
            assertEquals(List.of("status:invalid_format"), failures(malformed), bad);
        }
    }

    @Test
    void cursorValuesReturnsDecodedKeys() {
        String cursor = PageCursor.encode(Map.of("t", "2026-10-03T12:00:00.000Z", "i", "id-1"));
        QueryParams params = QueryParams.of(request("cursor", cursor));
        Map<String, String> values = params.cursorValues("t", "i").orElseThrow();
        assertEquals("id-1", values.get("i"));
        assertTrue(params.valid());
    }

    @Test
    void cursorFromAnotherListIsRejected() {
        String cursor = PageCursor.encode(Map.of("x", "1"));
        QueryParams params = QueryParams.of(request("cursor", cursor));
        assertTrue(params.cursorValues("t", "i").isEmpty());
        assertEquals(List.of("cursor:invalid_format"), failures(params));
    }

    @Test
    void garbageCursorIsRejected() {
        QueryParams params = QueryParams.of(request("cursor", "это-не-курсор"));
        assertTrue(params.cursorValues("t").isEmpty());
        assertEquals(List.of("cursor:invalid_format"), failures(params));
    }

    @Test
    void cursorLongerThanLimitIsRejected() {
        QueryParams params = QueryParams.of(request("cursor", "a".repeat(QueryParams.MAX_CURSOR_LENGTH + 1)));
        assertTrue(params.cursor().isEmpty());
        assertEquals(List.of("cursor:too_long"), failures(params));
    }

    @Test
    void errorsAccumulateAcrossParameters() {
        QueryParams params = QueryParams.of(request("limit", "0", "q", "x", "foo", "1"), "q");
        params.limit();
        params.text("q", 2, 5);
        assertFalse(params.valid());
        assertEquals(List.of("foo:unknown_value", "limit:out_of_range", "q:too_short"), failures(params));
    }
}
