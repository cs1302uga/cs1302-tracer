public class Driver {
    static void fail() {
        throw new IllegalStateException("worker failed");
    }
    static void survive() {
        System.out.println("survivor completed");
    }
    public static void main(String[] args) throws InterruptedException {
        Thread failed = new Thread(Driver::fail, "failed");
        Thread survivor = new Thread(Driver::survive, "survivor");
        failed.start();
        failed.join();
        survivor.start();
        survivor.join();
    }
}
