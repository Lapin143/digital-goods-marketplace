package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.platform.notification.api.DirectMail;
import dgm.platform.notification.api.DirectMailException;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.testing.RecordingEmail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Прямая отправка письма с кодом (Р-169): письмо собирается в памяти и сразу уходит провайдеру, в очередь и в базу не попадает; отказ провайдера
 * даёт ошибку без адреса и кода, повторов нет; шаблоны очереди напрямую не отправляются; метрика без адреса и кода.
 */
@Tag("TC-711")
class DirectMailServiceTest {

    private static final String TO = "new.address@mail.example";
    private static final String CODE = "042917";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private RecordingEmail provider;
    private SimpleMeterRegistry metrics;
    private DirectMailService service;

    @BeforeEach
    void setUp() {
        provider = new RecordingEmail();
        metrics = new SimpleMeterRegistry();
        service = new DirectMailService(new Letters(ZoneId.of("Europe/Moscow"), "МСК"), provider, new ManualClock(NOW), metrics);
    }

    private double count(String result) {
        var counter = metrics.find("notification_direct_total").tag("template", DirectMail.EMAIL_CHANGE_CODE).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theLetterGoesStraightToTheProviderWithTheCodeAndTheLifetime() {
        service.send(TO, DirectMail.EMAIL_CHANGE_CODE, Map.of("code", CODE));

        assertEquals(1, provider.accepted().size());
        OutgoingEmail mail = provider.accepted().get(0);
        assertEquals(TO, mail.to());
        assertEquals("Код подтверждения нового адреса e-mail", mail.subject());
        assertTrue(mail.text().contains(CODE), mail.text());
        assertTrue(mail.text().contains("5 минут"), mail.text());
        assertEquals(7, UUID.fromString(mail.messageId()).version(), "идентификатор сообщения UUIDv7");
        assertEquals(1.0, count("sent"));
        assertEquals(0.0, count("failed"));
    }

    @Test
    void aRefusalOfTheProviderGivesAnErrorWithoutAddressOrCodeAndNoRetry() {
        provider.failNext(1, "http-503", false);

        DirectMailException e = assertThrows(DirectMailException.class, () -> service.send(TO, DirectMail.EMAIL_CHANGE_CODE, Map.of("code", CODE)));

        assertFalse(e.getMessage().contains(TO), e.getMessage());
        assertFalse(e.getMessage().contains(CODE), e.getMessage());
        assertInstanceOf(EmailSendException.class, e.getCause());
        assertEquals(1, provider.attempts().size(), "повторов нет: код запрашивает покупатель");
        assertEquals(0, provider.accepted().size());
        assertEquals(1.0, count("failed"));
        assertEquals(0.0, count("sent"));
    }

    @Test
    void aPermanentRefusalIsTheSameErrorForTheCaller() {
        provider.failNext(1, "address-rejected", true);

        assertThrows(DirectMailException.class, () -> service.send(TO, DirectMail.EMAIL_CHANGE_CODE, Map.of("code", CODE)));

        assertEquals(1.0, count("failed"));
    }

    @Test
    void aTemplateOfTheQueueIsNotSentDirectly() {
        assertThrows(InvalidNotificationException.class, () -> service.send(TO, "seller-returned", Map.of("comment", "x")));
        assertThrows(InvalidNotificationException.class, () -> service.send(TO, "no-such-template", Map.of("code", CODE)));

        assertEquals(0, provider.attempts().size());
    }

    @Test
    void wrongParametersNeverReachTheProviderAndTheErrorHidesTheValue() {
        List<Map<String, Object>> cases = List.of(Map.of("code", "12345"), Map.of("code", "12345a"), Map.of(), Map.of("code", CODE, "to", TO));
        for (Map<String, Object> bad : cases) {
            InvalidNotificationException e = assertThrows(InvalidNotificationException.class, () -> service.send(TO, DirectMail.EMAIL_CHANGE_CODE, bad));
            assertFalse(e.getMessage().contains(TO), e.getMessage());
        }

        assertEquals(0, provider.attempts().size());
        assertEquals(0.0, count("sent"));
        assertEquals(0.0, count("failed"));
    }

    @Test
    void metricLabelsHoldOnlyTheTemplateAndTheResult() {
        service.send(TO, DirectMail.EMAIL_CHANGE_CODE, Map.of("code", CODE));

        metrics.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertFalse(tag.getValue().contains("@"), tag.toString());
            assertFalse(tag.getValue().contains(CODE), tag.toString());
        }));
    }
}
