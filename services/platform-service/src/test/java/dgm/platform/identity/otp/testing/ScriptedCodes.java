package dgm.platform.identity.otp.testing;

import dgm.platform.identity.otp.service.CodeSource;
import java.util.ArrayDeque;
import java.util.Deque;

/** Источник кодов для тестов: отдаёт заранее заданные коды по очереди, а когда они кончились, увеличивающиеся числа. */
public final class ScriptedCodes implements CodeSource {

    private final Deque<String> queue = new ArrayDeque<>();
    private int counter = 100_000;

    public ScriptedCodes then(String... codes) {
        for (String code : codes) {
            queue.add(code);
        }
        return this;
    }

    @Override
    public synchronized String next() {
        String scripted = queue.poll();
        return scripted != null ? scripted : String.format(java.util.Locale.ROOT, "%06d", counter++);
    }
}
