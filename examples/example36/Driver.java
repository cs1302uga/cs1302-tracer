public class Driver {
    static int count;
    static void work() {
        for (int i = 0; i < 3; i++) {
            int observed = count;
            Thread.yield();
            count = observed + 1;
        }
    }
    public static void main(String[] args) throws InterruptedException {
        Thread first = new Thread(Driver::work, "first");
        Thread second = new Thread(Driver::work, "second");
        first.start();
        second.start();
        first.join();
        second.join();
        System.out.println(count);
    }
}
