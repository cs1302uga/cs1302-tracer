package cs1302.tracer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SourceDelimitersTest {
  private static final Pattern LEGACY = Pattern.compile(
      "^//[ \t]*[-=]{3,}[ \t]*(.*?\\.java)[ \t]*[-=]{3,}[ \t]*$", Pattern.MULTILINE);

  @Test
  void utilityConstructorIsPrivate() throws Exception {
    var constructor = SourceDelimiters.class.getDeclaredConstructor();
    assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  void preservesSyntaxAndOffsets() {
    List<String> lines = List.of("", "/", "x/", "/x", "//", "// ", "//--", "//---",
        "//--- ", "//---===", "//--- A.java", "//--- A.java --", "//--- A.jav ---",
        "//---A.java---", "//---\t A.java \t=== \t", "//---.java---", "//---A.java---.java---",
        "//---A.java---x", "//---A.java--=", "//===-A.java---", " //--- A.java ---",
        "//--- \fA.java ---", "//--- A.java\f---", "//--- A.java\u0000---", "//--- A.java --- ");
    for (String ending : List.of("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
      assertSameAsLegacy(String.join(ending, lines));
    }
    assertSameAsLegacy("");
    Random random = new Random(1302);
    String[] parts = {"/", "-", "=", " ", "\t", ".java", "X", "\n", "\r", "\f"};
    for (int i = 0; i < 5000; i++) {
      StringBuilder source = new StringBuilder("//");
      for (int n = random.nextInt(20); n > 0; n--) {
        source.append(parts[random.nextInt(parts.length)]);
      }
      assertSameAsLegacy(source.toString());
    }
  }

  @SuppressWarnings("deprecation") // Intentionally verify the public compatibility matcher.
  private void assertSameAsLegacy(String source) {
    var matcher = LEGACY.matcher(source);
    List<SourceDelimiters.Delimiter> expected = new ArrayList<>();
    while (matcher.find()) {
      expected.add(new SourceDelimiters.Delimiter(matcher.start(), matcher.end(), matcher.group(1).trim()));
    }
    assertThat(SourceDelimiters.scan(source)).as("source %s", source).isEqualTo(expected);
    var compatible = CompilationHelper.DELIMITER_PATTERN.matcher(source);
    List<SourceDelimiters.Delimiter> actual = new ArrayList<>();
    while (compatible.find()) {
      actual.add(new SourceDelimiters.Delimiter(compatible.start(), compatible.end(), compatible.group(1).trim()));
    }
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @SuppressWarnings("deprecation") // The compatibility matcher must also resist adversarial input.
  void boundsWorkForAdversarialWhitespaceAndMarkers() {
    for (String source : List.of("//---" + "\t".repeat(100_000) + "!",
        "//" + "-".repeat(100_000), "//---A.java" + "-".repeat(100_000) + "!")) {
      Counted input = new Counted(source);
      assertThat(SourceDelimiters.scan(input)).isEmpty();
      assertThat(input.reads).isLessThan(source.length() * 8);
      Counted compatible = new Counted(source);
      assertThat(CompilationHelper.DELIMITER_PATTERN.matcher(compatible).find()).isFalse();
      assertThat(compatible.reads).isLessThan(source.length() * 12);
    }
  }

  private static final class Counted implements CharSequence {
    private final String value;
    private int reads;
    Counted(String value) { this.value = value; }
    public int length() { return value.length(); }
    public char charAt(int i) { reads++; return value.charAt(i); }
    public CharSequence subSequence(int start, int end) { return value.subSequence(start, end); }
    public String toString() { return value; }
  }
}
