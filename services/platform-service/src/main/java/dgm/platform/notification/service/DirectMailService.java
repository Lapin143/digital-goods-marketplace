package dgm.platform.notification.service;

import dgm.kit.id.Uuids;
import dgm.platform.notification.api.DirectMail;
import dgm.platform.notification.api.DirectMailException;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.letter.Letter;
import dgm.platform.notification.letter.Letters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Прямая отправка писем с одноразовым кодом (компонент {@code notification-service}, Р-169): шаблон собирается в памяти и передаётся
 * {@link EmailSender} сразу, строки в очереди нет, поэтому код нигде не хранится. Метрика {@code notification_direct_total{template,result}},
 * результат {@code sent} или {@code failed}. Ни адрес, ни код, ни текст письма в журнал и метрики не попадают.
 */
public final class DirectMailService implements DirectMail {

    private static final Logger LOG = LoggerFactory.getLogger(DirectMailService.class);

    private final Letters letters;
    private final EmailSender sender;
    private final Clock clock;
    private final MeterRegistry metrics;

    public DirectMailService(Letters letters, EmailSender sender, Clock clock, MeterRegistry metrics) {
        this.letters = letters;
        this.sender = sender;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public void send(String to, String template, Map<String, Object> params) {
        if (!letters.directOnly().contains(template)) {
            throw new InvalidNotificationException("Шаблон " + template + " не предназначен для прямой отправки");
        }
        Letter letter = letters.render(template, null, params);
        try {
            sender.send(new OutgoingEmail(Uuids.v7(clock).toString(), to, letter.subject(), letter.text()));
        } catch (EmailSendException e) {
            count(template, "failed");
            LOG.warn("Провайдер не принял письмо {} при прямой отправке: {}", template, e.code());
            throw new DirectMailException("Провайдер почты не принял письмо (" + e.code() + ")", e);
        }
        count(template, "sent");
    }

    private void count(String template, String result) {
        metrics.counter("notification_direct_total", "template", template, "result", result).increment();
    }
}
