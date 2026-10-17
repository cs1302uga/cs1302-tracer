package cs1302.tracer.internal.guest;

import org.apiguardian.api.API;

import java.io.IOException;
import java.nio.file.Path;

/** Keeps native JVM main invocation for dedicated guests with a prepared dependency class path. */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class GuestSystemClassLoader extends GuestClassLoader {

    /** System property identifying the host-generated class-path descriptor. */
    public static final String DESCRIPTOR_PROPERTY = "cs1302.tracer.guest.classPath";

    /**
     * Constructor invoked by the JVM when selecting its system class loader.
     *
     * <p>The supplied initial application loader owns only the tracer's loader implementation. Application
     * lookup delegates to the platform loader, keeping tracer libraries out of the submitted program's path.</p>
     *
     * @param ignored Initial loader required by the JVM constructor contract.
     * @throws IOException If the descriptor or an archive cannot be read.
     */
    public GuestSystemClassLoader(ClassLoader ignored) throws IOException {
        super(GuestClassPath.read(Path.of(System.getProperty(DESCRIPTOR_PROPERTY))));
    } // GuestSystemClassLoader

} // GuestSystemClassLoader
