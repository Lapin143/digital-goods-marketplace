package dgm.platform.identity.otp.testing;

import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.SmsSendException;
import dgm.platform.identity.otp.service.SmsSender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Провайдер SMS в памяти: запоминает отправленные сообщения, может отказывать. Потокобезопасен. */
public final class RecordingSms implements SmsSender {

    /** Отправленное сообщение. */
    public record Message(String to, String text) {

        /** Код из текста: первая группа из шести цифр. */
        public String code() {
            Matcher m = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(text);
            if (!m.find()) {
                throw new IllegalStateException("В тексте SMS нет кода");
            }
            return m.group(1);
        }
    }

    private final List<Message> sent = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean failing;

    public void failing(boolean value) {
        this.failing = value;
    }

    @Override
    public void send(PhoneNumber phone, String text) {
        if (failing) {
            throw new SmsSendException("Провайдер SMS ответил кодом 503");
        }
        sent.add(new Message(phone.value(), text));
    }

    public List<Message> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public Optional<Message> last() {
        synchronized (sent) {
            return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
        }
    }

    public int count() {
        return sent.size();
    }
}
