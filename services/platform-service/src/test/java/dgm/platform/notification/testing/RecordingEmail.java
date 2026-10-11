package dgm.platform.notification.testing;

import dgm.platform.notification.service.EmailSendException;
import dgm.platform.notification.service.EmailSender;
import dgm.platform.notification.service.OutgoingEmail;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Провайдер почты теста: записывает письма, а исходы берёт из сценария (по умолчанию письмо принято). */
public final class RecordingEmail implements EmailSender {

    private final List<OutgoingEmail> accepted = new ArrayList<>();
    private final List<OutgoingEmail> attempts = new ArrayList<>();
    private final Deque<RuntimeException> script = new ArrayDeque<>();

    /** Следующий вызов закончится этой ошибкой. */
    public RecordingEmail failNext(RuntimeException error) {
        script.add(error);
        return this;
    }

    public RecordingEmail failNext(int times, String code, boolean permanent) {
        for (int i = 0; i < times; i++) {
            script.add(new EmailSendException(code, permanent, "сбой теста"));
        }
        return this;
    }

    @Override
    public void send(OutgoingEmail mail) {
        attempts.add(mail);
        RuntimeException error = script.poll();
        if (error != null) {
            throw error;
        }
        accepted.add(mail);
    }

    /** Письма, которые провайдер принял. */
    public List<OutgoingEmail> accepted() {
        return List.copyOf(accepted);
    }

    /** Все вызовы, в том числе неудачные. */
    public List<OutgoingEmail> attempts() {
        return List.copyOf(attempts);
    }
}
