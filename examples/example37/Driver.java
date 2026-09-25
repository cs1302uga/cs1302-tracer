public class Driver {
    static final Object lock = new Object();
    static boolean ready;
    static void await() {
        synchronized (lock) {
            while (!ready) {
                try {
                    lock.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("ready");
        }
    }
    public static void main(String[] args) throws InterruptedException {
        Thread waiter = new Thread(Driver::await, "waiter");
        waiter.start();
        synchronized (lock) {
            ready = true;
            lock.notifyAll();
        }
        waiter.join();
    }
}
