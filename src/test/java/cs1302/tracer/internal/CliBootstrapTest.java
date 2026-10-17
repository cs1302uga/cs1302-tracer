package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CliBootstrapTest {

    @Test
    void entryPointDiagnosesOldJavaBeforeLoadingCli() throws Exception {
        String specification = System.getProperty("java.specification.version");
        var oldExit = CliBootstrap.exit;
        PrintStream oldErr = System.err;
        PrintStream oldOut = System.out;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger status = new AtomicInteger(-1);
        try {
            CliBootstrap.exit = status::set;
            System.setErr(new PrintStream(errors));
            System.setOut(new PrintStream(output));
            System.setProperty("java.specification.version", "1.8");
            CliBootstrap.main(new String[] {"--help"});
            assertThat(status.get()).isEqualTo(1);
            assertThat(errors.toString()).contains("JDK 21 or later");
            assertThat(output.toString()).isEmpty();
            System.setProperty("java.specification.version", specification);
            CliBootstrap.main(new String[] {"--version"});
            assertThat(status.get()).isZero();
            assertThat(output.toString()).startsWith("code-tracer ");
        } finally {
            System.setProperty("java.specification.version", specification);
            CliBootstrap.exit = oldExit;
            System.setErr(oldErr);
            System.setOut(oldOut);
        }
        var constructor = CliBootstrap.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }
}
