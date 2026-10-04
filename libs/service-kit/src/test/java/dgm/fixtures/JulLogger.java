package dgm.fixtures;

import java.util.logging.Logger;

/** Нарушение: журнал не через SLF4J. */
public final class JulLogger {

    private static final Logger LOG = Logger.getLogger(JulLogger.class.getName());

    public void write(String text) {
        LOG.info(text);
    }
}
