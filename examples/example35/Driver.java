public class Driver {
    static class Counter {
        int value;
        synchronized void increment() {
            value++;
        }
    }
    static final Counter counter = new Counter();
    static void work() {
        Counter shared = counter;
        for (int i = 0; i < 3; i++) {
            shared.increment();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        Thread first = new Thread(Driver::work, "first");
        Thread second = new Thread(Driver::work, "second");
        first.start();
        second.start();
        first.join();
        second.join();
        System.out.println(counter.value);
    }
}
