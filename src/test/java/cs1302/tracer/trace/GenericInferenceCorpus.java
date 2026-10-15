package cs1302.tracer.trace;

import java.util.List;

/** Minimal valid programs shared by the compiler oracle and resolver assessment. */
final class GenericInferenceCorpus {
    record Case(String name, String parameters, String setup, String invocation,
            String oracleType, String baseline, String prototype) {
        String source() {
            return """
                    import java.util.*;
                    public class Main {
                        static class Person {}
                        static <T> T helper(%s) {
                            T local = null;
                            System.out.println(local); // capture
                            return local;
                        }
                        public static void main(String[] args) {
                            %s
                            %s;
                        }
                    }
                    """.formatted(parameters, setup, invocation);
        }
    }

    static List<Case> cases() {
        return List.of(
                new Case("variable control", "T item", "String s = \"a\";", "helper(s)",
                        "java.lang.String", "java.lang.String", "java.lang.String"),
                new Case("declared supertype control", "T item", "Number n = 1;", "helper(n)",
                        "java.lang.Number", "java.lang.Number", "java.lang.Number"),
                new Case("explicit wider control", "T item", "", "Main.<Number>helper(1)",
                        "java.lang.Number", "java.lang.Number", ""),
                new Case("literal", "T item", "", "helper(\"a\")",
                        "java.lang.String", "", "java.lang.String"),
                new Case("creation", "T item", "", "helper(new Person())",
                        "Main.Person", "", "Main.Person"),
                new Case("non generic call", "T item", "", "helper(String.valueOf(42))",
                        "java.lang.String", "", "java.lang.String"),
                new Case("boxing deferred", "T item", "", "helper(1)",
                        "java.lang.Integer", "", ""),
                new Case("parameterized", "List<T> items", "List<String> x = List.of(\"a\");", "helper(x)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("nested parameterized", "List<List<T>> items", "List<List<String>> x = List.of();", "helper(x)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("parameterized array", "List<T[]> items", "List<String[]> x = List.of();", "helper(x)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("inherited parameterization deferred", "List<T> items", "ArrayList<String> x = new ArrayList<>();", "helper(x)",
                        "java.lang.String", "", ""),
                new Case("consistent arguments", "T a, T b", "String a = \"a\", b = \"b\";", "helper(a, b)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("conflicting arguments", "T a, T b", "String a = \"a\"; Object b = new Object();", "helper(a, b)",
                        "java.lang.Object", "", ""),
                new Case("mixed constraints", "List<T> a, T b", "List<String> a = List.of(); String b = \"b\";", "helper(a, b)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("fixed varargs", "T... items", "String[] a = {\"a\"};", "helper(a)",
                        "java.lang.String", "", "java.lang.String"),
                new Case("expanded varargs", "T... items", "String a = \"a\";", "helper(a)",
                        "java.lang.String", "", ""),
                new Case("primitive array control", "T item", "int[] a = {1};", "helper(a)",
                        "int[]", "int[]", "int[]"),
                new Case("primitive array varargs", "T... items", "int[] a = {1};", "helper(a)",
                        "int[]", "", ""),
                new Case("target typing", "", "", "String result = helper()",
                        "java.lang.String", "", ""),
                new Case("null target", "T item", "", "String result = helper(null)",
                        "java.lang.String", "", ""),
                new Case("wildcard containing", "T item", "List<? extends Number> a = List.of(1);", "helper(a)",
                        "java.util.List<capture#", "", ""),
                new Case("capture", "List<T> items", "List<? extends Number> a = List.of(1);", "helper(a)",
                        "capture#", "", ""),
                new Case("intersection", "T item", "", "helper((Runnable & java.io.Serializable) () -> {})",
                        "java.lang.Object&java.lang.Runnable&java.io.Serializable", "", ""),
                new Case("raw parameterized", "List<T> items", "List a = new ArrayList();", "helper(a)",
                        "java.lang.Object", "", ""),
                new Case("nested generic call", "T item", "", "helper(Collections.emptyList())",
                        "java.util.List<java.lang.Object>", "", ""),
                new Case("diamond creation", "T item", "", "helper(new ArrayList<>())",
                        "java.util.ArrayList<java.lang.Object>", "", "")
        );
    }
}
