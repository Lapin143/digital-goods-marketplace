package dgm.fixtures;

/** Нарушение: вывод в стандартный поток. */
public final class ConsoleWriter {

    public void write(String text) {
        System.out.println(text);
    }
}
