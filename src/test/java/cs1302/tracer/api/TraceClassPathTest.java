package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TraceClassPathTest {

    @Test
    void emptyDefaultsAndDetachedBuildersDoNotAccessTheFilesystem() {
        TraceClassPath empty = TraceClassPath.empty();
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.copy()).isFalse();
        assertThat(empty.followLinks()).isFalse();
        Path missing = Path.of("does-not-exist", "dependency.jar");
        TraceClassPath.Builder builder = TraceClassPath.builder().addPath(missing)
                .addGlob(Path.of("missing-root"), "**/*.jar").copy(true).followLinks(true);
        TraceClassPath first = builder.build();
        builder.addPath(Path.of("later.jar")).copy(false).followLinks(false);
        TraceClassPath second = builder.build();
        assertThat(first.entries()).containsExactly(new TraceClassPath.PathEntry(missing),
                new TraceClassPath.GlobEntry(Path.of("missing-root"), "**/*.jar"));
        assertThat(first.copy()).isTrue();
        assertThat(first.followLinks()).isTrue();
        assertThat(second.entries()).hasSize(3);
        assertThat(second.copy()).isFalse();
        assertThat(second.followLinks()).isFalse();
        TraceClassPath rebuilt = first.toBuilder().addPath(Path.of("another.jar")).build();
        assertThat(first.entries()).hasSize(2);
        assertThat(rebuilt.entries()).hasSize(3);
        assertThat(rebuilt.copy()).isTrue();
        assertThat(rebuilt.followLinks()).isTrue();
        assertThatThrownBy(() -> first.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(TraceClassPath.builder().build().entries()).isEmpty();
        assertThat(empty.entries()).isEmpty();
    }

    @Test
    void typedPathsAreLiteralAndInvalidAdditionsDoNotMutateTheBuilder() {
        Path literal = Path.of("literal-*.jar");
        TraceClassPath.Builder builder = TraceClassPath.builder().addPath(literal);
        assertThatThrownBy(() -> builder.addPath(null)).isInstanceOf(NullPointerException.class).hasMessage("path");
        assertThatThrownBy(() -> builder.addGlob(null, "*.jar"))
                .isInstanceOf(NullPointerException.class).hasMessage("root");
        assertThatThrownBy(() -> builder.addGlob(Path.of("."), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addGlob(Path.of("."), "["))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build().entries()).containsExactly(new TraceClassPath.PathEntry(literal));
    }

}
