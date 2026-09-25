import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Driver {
    static void work() {
        int value = 7;
        System.out.println(value);
    }
    public static void main(String[] args) throws InterruptedException {
        var pool = Executors.newFixedThreadPool(2);
        pool.execute(Driver::work);
        pool.execute(Driver::work);
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.DAYS);
        System.out.println("closed");
    }
}
