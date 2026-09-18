package cs1302.tracer.serialize;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Spools JSON so serialization failures never publish a partial payload. */
public final class JsonOutput implements AutoCloseable {
    private final Path file;

    /**
     * Creates a temporary output spool.
     * @throws IOException If temporary storage cannot be created.
     */
    public JsonOutput() throws IOException {
        file = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")),
                "tracer-json-", ".json");
    } // JsonOutput

    /**
     * Serializes one model without retaining the full JSON text in Java memory.
     * @param gson Configured serializer.
     * @param model Complete output model, possibly containing lazy steps.
     * @throws IOException If temporary storage cannot be written.
     */
    public void write(Gson gson, Object model) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(model, writer);
        } // try
    } // write

    /**
     * Publishes the completed payload followed by a newline.
     * @throws IOException If the spool cannot be read.
     */
    public void publish() throws IOException {
        Files.copy(file, System.out);
        System.out.println();
    } // publish

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    } // close
} // JsonOutput
