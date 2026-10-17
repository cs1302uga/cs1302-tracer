package cs1302.tracer.internal.guest;

import org.apiguardian.api.API;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exchanges an already resolved class path with guests without path-list escaping or manifest expansion. */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class GuestClassPath {

    private static final int FORMAT = 0x43545031;

    /** Prevents instantiation. */
    private GuestClassPath() {} // GuestClassPath

    /**
     * Writes a descriptor owned by the compilation output directory.
     *
     * @param output Compiled output, searched first.
     * @param dependencies Prepared dependency locations in search order.
     * @return Descriptor path.
     * @throws IOException If the descriptor cannot be written.
     */
    public static Path write(Path output, List<Path> dependencies) throws IOException {
        Path descriptor = Files.createTempFile(output, ".tracer-class-path-", ".bin");
        try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(descriptor))) {
            stream.writeInt(FORMAT);
            stream.writeInt(dependencies.size() + 1);
            stream.writeUTF(output.toAbsolutePath().toString());
            for (Path dependency : dependencies) {
                stream.writeUTF(dependency.toAbsolutePath().toString());
            } // for
        } // try
        return descriptor;
    } // write

    /**
     * Reads the exact prepared locations without interpreting wildcards or manifests.
     *
     * @param descriptor Host-generated descriptor.
     * @return Immutable search path.
     * @throws IOException If the descriptor is invalid or cannot be read.
     */
    public static List<Path> read(Path descriptor) throws IOException {
        try (DataInputStream stream = new DataInputStream(Files.newInputStream(descriptor))) {
            if (stream.readInt() != FORMAT) {
                throw new IOException("Unsupported guest class-path descriptor: " + descriptor);
            } // if
            int count = stream.readInt();
            if (count <= 0) {
                throw new IOException("Empty guest class-path descriptor: " + descriptor);
            } // if
            List<Path> paths = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                paths.add(Path.of(stream.readUTF()));
            } // for
            if (stream.read() != -1) {
                throw new IOException("Trailing data in guest class-path descriptor: " + descriptor);
            } // if
            return List.copyOf(paths);
        } // try
    } // read

} // GuestClassPath
