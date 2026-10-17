package example.library;

/** A small library compiled separately from the traced source. */
public final class Answer {

    private Answer() {} // Answer

    public static int value() {
        return 42;
    } // value

} // Answer
