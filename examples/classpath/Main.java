import example.library.Answer;

/** Demonstrates a traced program using a precompiled library. */
public class Main {

    public static void main(String[] args) {
        int answer = Answer.value();
        System.out.println(answer);
    } // main

} // Main
