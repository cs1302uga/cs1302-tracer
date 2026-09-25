import java.util.concurrent.Executors;

public class Driver {
    static void work() {
        System.out.println("task completed");
    }
    public static void main(String[] args) {
        var pool = Executors.newSingleThreadExecutor();
        pool.execute(Driver::work);
        // Deliberately omit shutdown: the idle non-daemon worker keeps the JVM alive.
    }
}
