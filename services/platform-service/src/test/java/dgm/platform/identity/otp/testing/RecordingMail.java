package dgm.platform.identity.otp.testing;

import dgm.platform.identity.otp.service.EmailCodeSendException;
import dgm.platform.identity.otp.service.EmailCodeSender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Отправитель письма с кодом в памяти: запоминает адрес и код, может отказывать. Потокобезопасен. */
public final class RecordingMail implements EmailCodeSender {

    /** Отправленное письмо. */
    public record Message(String to, String code) {
    }

    private final List<Message> sent = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean failing;

    public void failing(boolean value) {
        this.failing = value;
    }

    @Override
    public void send(String to, String code) {
        if (failing) {
            throw new EmailCodeSendException("Провайдер почты ответил кодом 503");
        }
        sent.add(new Message(to, code));
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
}
