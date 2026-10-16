/** Demonstrates primitive values whose binary encodings help explain their meaning. */
public class Main {
    static short minimum = Short.MIN_VALUE;
    char letter = 'A';

    public static void main(String[] args) {
        byte delta = -1;
        long exact = 9007199254740993L;
        float negativeZero = -0.0f;
        double fraction = 1.5;
        boolean ready = true;
        int[] values = { 0, -1, Integer.MIN_VALUE };
        Byte boxed = delta;
        Main object = new Main();
        System.out.println(delta + " " + exact + " " + negativeZero + " " + fraction + " " + ready);
        System.out.println(values.length + " " + boxed + " " + object.letter + " " + minimum);
    }
}
