import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class Main {
    static Set<String> backing = new LinkedHashSet<>();
    static Set<String> view = Collections.unmodifiableSet(backing);
    static Set<String> alias = view;

    public static void main(String[] args) {
        backing.add("first");
        backing.add(null);
        System.out.println(view);
        backing.add("last");
        System.out.println(alias);
    }
}
