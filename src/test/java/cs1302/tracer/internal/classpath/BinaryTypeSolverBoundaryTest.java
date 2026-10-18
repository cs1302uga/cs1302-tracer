package cs1302.tracer.internal.classpath;

import static org.assertj.core.api.Assertions.*;

import cs1302.tracer.internal.FaultPaths;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javassist.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryTypeSolverBoundaryTest {

    @TempDir Path root;

    @Test
    void lookupReportsMissingClassesAndUsesPlatformResourcesWithoutGuestLoading() throws Exception {
        var lookup = new BinaryTypeSolver.BinaryLookup(List.of(root));
        assertThat(lookup.find("missing.Type")).isNull();
        assertThat(lookup.openClassfile("missing.Type")).isNull();
        assertThat(lookup.find("java.lang.Object").getProtocol()).isEqualTo("jrt");
        try (var bytes = lookup.openClassfile("java.lang.Object")) {
            assertThat(bytes.readNBytes(4)).containsExactly((byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE);
        }
        Path entry = Files.write(root.resolve("Example.class"), new byte[] {1, 2});
        assertThat(lookup.find("Example").toURI()).isEqualTo(entry.toUri());
        try (var bytes = lookup.openClassfile("Example")) {
            assertThat(bytes.readAllBytes()).containsExactly((byte) 1, (byte) 2);
        }
    }

    @Test
    void deniedBytecodeReadsRetainFilesystemCause() throws Exception {
        Files.write(root.resolve("Example.class"), new byte[] {1});
        var lookup = new BinaryTypeSolver.BinaryLookup(List.of(FaultPaths.refusingReads(root)));
        assertThatThrownBy(() -> lookup.openClassfile("Example"))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("Example")
                .hasMessageContaining(AccessDeniedException.class.getSimpleName());
    }

    @Test
    void unsupportedResourceUrlRetainsConversionFailure() throws Exception {
        Files.write(root.resolve("Example.class"), new byte[] {1});
        var lookup = new BinaryTypeSolver.BinaryLookup(List.of(FaultPaths.withUnknownUrlProtocol(root)));
        assertThatThrownBy(() -> lookup.find("Example"))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(java.net.MalformedURLException.class);
    }

    @Test
    void disappearingBorrowedBytecodeReportsTheTypeBeingResolved() throws Exception {
        Path entry = Files.write(root.resolve("Example.class"), new byte[] {1});
        var solver = new BinaryTypeSolver(List.of(FaultPaths.vanishingAfterAttributes(root)));
        assertThatThrownBy(() -> solver.solveType("Example"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Example")
                .hasCauseInstanceOf(NotFoundException.class);
        assertThat(entry).doesNotExist();
    }

    @Test
    void detachedSignatureEvidenceIsImmutableAndNeverRequiresGuestClassLoading() {
        var solver = new BinaryTypeSolver(List.of());
        assertThat(solver.declarationSignatures("missing.Type")).isEmpty();
        assertThat(solver.declarationSignatures("java.lang.Object")).isEmpty();
        var string = solver.declarationSignatures("java.lang.String").orElseThrow();
        assertThat(string.fields()).containsKey("value");
        assertThatThrownBy(() -> string.fields().put("invented", "I"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(solver.declarationSignatures("java.util.List").orElseThrow().hierarchy())
                .contains("<E:", "Ljava/lang/Object;", "<TE;>;");
    }

}
