public class Driver {
    static void background() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
    static void finish() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        System.out.println("worker completed");
    }
    public static void main(String[] args) {
        Thread daemon = new Thread(Driver::background, "daemon");
        daemon.setDaemon(true);
        daemon.start();
        Thread worker = new Thread(Driver::finish, "worker");
        worker.start();
        System.out.println("main returning");
    }
}
