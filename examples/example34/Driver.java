public class Driver {
    static void work() {
        int value = 1;
        value++;
        System.out.println(value);
    }
    public static void main(String[] args) throws InterruptedException {
        Thread first = new Thread(Driver::work, "first");
        Thread second = new Thread(Driver::work, "second");
        first.start();
        second.start();
        first.join();
        second.join();
        System.out.println("joined");
    }
}
