package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import com.github.javaparser.StaticJavaParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class LocalMetadataTest {
    @Test void respectsCallableAndLexicalOwnership() {
        String source = """
                package example;
                class C {
                  final int field = 1;
                  C(final int p) {
                    int local = p;
                  }
                  void m(final int p) {
                    { final int x = p; System.out.println(x); }
                    { int x = p; x++; }
                    Runnable action = () -> { final int hidden = 1; };
                    int hidden = 2;
                    for (final int i : new int[] {1}) {
                      System.out.println(i);
                    }
                    try (final var resource = new java.io.StringReader("")) {
                      resource.read();
                    } catch (final Exception e) {
                      System.out.println(e);
                    }
                  }
                  void m(String p) {
                    System.out.println(p);
                  }
                  static class Nested {
                    void m(final int nested) {
                      System.out.println(nested);
                    }
                  }
                }
                """;
        var metadata = new LocalMetadata(Arrays.asList(null, new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT))
                .parse(source).getResult().orElseThrow()));
        assertThat(metadata.isFinal("example.C", "<init>", "p", 5)).isTrue();
        assertThat(metadata.isFinal("example.C", "<init>", "local", 5)).isFalse();
        assertThat(metadata.isFinal("example.C", "m", "p", 8)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "x", 8)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "x", 9)).isFalse();
        assertThat(metadata.isFinal("example.C", "m", "hidden", 11)).isFalse();
        assertThat(metadata.isFinal("example.C", "m", "i", 13)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "i", 15)).isFalse();
        assertThat(metadata.isFinal("example.C", "m", "resource", 16)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "resource", 18)).isFalse();
        assertThat(metadata.isFinal("example.C", "m", "e", 18)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "p", 22)).isFalse();
        assertThat(metadata.isFinal("example.C$Nested", "m", "nested", 26)).isTrue();
        assertThat(metadata.isFinal("example.C", "m", "nested", 26)).isFalse();
    }
    @Test void handlesLoopsSwitchesRecordsAndAnonymousCallableOwnership() {
        String source = """
                record R(int component) {}
                class C {
                  void m() {
                    for (final int i = 0; i < 1;) {
                      System.out.println(i);
                      break;
                    }
                    switch (1) {
                      case 1: final int x = Integer.parseInt("1");
                        System.out.println(x); break;
                    }
                    Runnable r = new Runnable() {
                      public void run() { final int inner = 1; }
                    };
                  }
                }
                """;
        var metadata = new LocalMetadata(List.of(new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT))
                .parse(source).getResult().orElseThrow()));
        assertThat(metadata.isFinal("C", "m", "i", 5)).isTrue();
        assertThat(metadata.isFinal("C", "m", "x", 10)).isTrue();
        assertThat(metadata.isFinal("C", "run", "inner", 13)).isFalse();
    }
}
