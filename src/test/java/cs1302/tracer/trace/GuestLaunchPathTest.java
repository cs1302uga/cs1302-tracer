package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.CompilationHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuestLaunchPathTest {
  @TempDir Path directory;

  @Test
  void utilityConstructorIsPrivate() throws Exception {
    var constructor = GuestLauncher.class.getDeclaredConstructor();
    assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  void launchesFromAClasspathContainingSpacesAndQuotes() throws Exception {
    try (var original = CompilationHelper.compile(
        "public class Main { public static void main(String[] args) { int value = 1; } }")) {
      Path destination = directory.resolve(" space \" double ' single");
      Files.move(original.classPath(), destination);
      try (var relocated = new CompilationHelper.CompilationResult(destination,
          original.compiledClassNames(), original.mainClass(), original.sourceRoot(),
          original.previewEnabled())) {
        assertThat(DebugTraceHelper.traceChronological(relocated, java.util.List.of(),
            java.util.List.of(), true)).isNotEmpty();
      }
    }
  }
}
