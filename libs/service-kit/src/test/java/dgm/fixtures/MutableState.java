package dgm.fixtures;

/** Нарушение: изменяемое статическое поле. */
public final class MutableState {

    static int counter;

    public int next() {
        return ++counter;
    }
}
