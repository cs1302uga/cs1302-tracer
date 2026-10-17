package cs1302.tracer.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import cs1302.tracer.api.TraceClassPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Batch dependency syntax retains intent, order, omission, and validation ownership. */
class BatchClassPathEntryTest {

    private final Gson gson = new Gson();

    @Test
    void stringsAreLiteralAndObjectsSelectPatternsWithoutNativeListSplitting() {
        BatchJobRequest request = this.gson.fromJson("""
                {"classPath":["literal*:name.jar", {"root":"libraries", "glob":"**/*.jar"},
                              {"path":"classes", "root":null, "glob":null}],
                 "copyClassPath":true, "followClassPathLinks":true}
                """, BatchJobRequest.class);
        TraceClassPath resolved = request.resolveClassPath();
        assertThat(resolved.entries()).containsExactly(
                new TraceClassPath.PathEntry(Path.of("literal*:name.jar")),
                new TraceClassPath.GlobEntry(Path.of("libraries"), "**/*.jar"),
                new TraceClassPath.PathEntry(Path.of("classes")));
        assertThat(resolved.copy()).isTrue();
        assertThat(resolved.followLinks()).isTrue();
        assertThat(this.gson.fromJson(this.gson.toJson(request), BatchJobRequest.class)).isEqualTo(request);
    }

    @Test
    void omittedAndExplicitFalseRemainDistinctWhileResolvingToDefaults() {
        BatchJobRequest omitted = BatchJobRequest.builder().build();
        BatchJobRequest explicit = omitted.toBuilder().classPath(List.of()).copyClassPath(false)
                .followClassPathLinks(false).build();
        assertThat(omitted.resolveClassPath().entries()).isEmpty();
        assertThat(omitted.resolveClassPath().copy()).isFalse();
        assertThat(omitted.resolveClassPath().followLinks()).isFalse();
        assertThat(this.gson.toJson(omitted)).isEqualTo("{}");
        assertThat(this.gson.toJson(explicit)).contains("\"classPath\":[]", "\"copyClassPath\":false",
                "\"followClassPathLinks\":false");
        assertThat(this.gson.fromJson(this.gson.toJson(explicit), BatchJobRequest.class)).isEqualTo(explicit);
        assertThat(explicit.toBuilder().build()).isEqualTo(explicit);
    }

    @Test
    void builderDetachesSelectionsWhilePreservingNullForDeferredValidation() {
        List<BatchClassPathEntry> selections = new ArrayList<>(Arrays.asList(
                new BatchClassPathEntry("classes", null, null), null));
        BatchJobRequest.Builder builder = BatchJobRequest.builder().classPath(selections);
        selections.clear();
        BatchJobRequest request = builder.build();
        assertThat(request.classPath()).hasSize(2);
        assertThatThrownBy(() -> request.classPath().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(request::resolveClassPath).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        assertThat(builder.classPath(null).build().classPath()).isNull();
        assertThat(request.toBuilder().classPath(null).copyClassPath(null).followClassPathLinks(null).build())
                .isEqualTo(BatchJobRequest.builder().build());
    }

    @Test
    void invalidSelectionShapesAreJobValidationErrors() {
        for (String json : List.of("{}", "{\"path\":\"a\",\"root\":\"b\"}",
                "{\"path\":\"a\",\"glob\":\"*\"}", "{\"root\":\"b\"}", "{\"glob\":\"*\"}")) {
            BatchClassPathEntry selection = this.gson.fromJson(json, BatchClassPathEntry.class);
            assertThatThrownBy(() -> selection.addTo(TraceClassPath.builder()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("literal path");
        }
    }

    @Test
    void wrongJsonTypesAreParseErrorsInsteadOfCoercedPaths() {
        for (String json : List.of("false", "123", "[]", "{\"path\":12}", "{\"root\":false}",
                "{\"glob\":[]}", "{\"path\":{}}")) {
            assertThatThrownBy(() -> this.gson.fromJson(json, BatchClassPathEntry.class))
                    .isInstanceOf(JsonParseException.class).hasMessageContaining("classPath");
        }
    }

} // BatchClassPathEntryTest
