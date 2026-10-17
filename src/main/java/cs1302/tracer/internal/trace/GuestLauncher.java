package cs1302.tracer.internal.trace;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import cs1302.tracer.internal.guest.GuestClassPath;
import cs1302.tracer.internal.guest.GuestSystemClassLoader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Configures the JDI argument tokenizer for literal guest classpaths. */
final class GuestLauncher {

    /** Prevents instantiation. */
    private GuestLauncher() {} // GuestLauncher

    /**
     * Launches a suspended guest with a single literal classpath entry.
     *
     * @param mainClass Guest entry point.
     * @param classPath Compiled classes or harness location.
     * @param preview Whether preview bytecode is enabled.
     * @return Suspended guest VM.
     * @throws IOException On process creation failure.
     * @throws IllegalConnectorArgumentsException On invalid JDI arguments.
     * @throws VMStartException On guest startup failure.
     */
    static VirtualMachine launch(String mainClass, Path classPath, boolean preview)
            throws IOException, IllegalConnectorArgumentsException, VMStartException {
        return launch(mainClass, classPath, preview, null);
    } // launch

    /**
     * Launches a dedicated guest using its native main invocation and the prepared dependency order.
     *
     * @param mainClass Guest entry point.
     * @param output Compiled job output.
     * @param dependencies Resolved dependencies; manifests must not be expanded again.
     * @param preview Whether preview bytecode is enabled.
     * @return Suspended guest VM.
     * @throws IOException On descriptor or process creation failure.
     * @throws IllegalConnectorArgumentsException On invalid JDI arguments.
     * @throws VMStartException On guest startup failure.
     */
    static VirtualMachine launch(String mainClass, Path output, List<Path> dependencies, boolean preview)
            throws IOException, IllegalConnectorArgumentsException, VMStartException {
        if (dependencies.isEmpty()) {
            return launch(mainClass, output, preview);
        } // if
        Path descriptor = GuestClassPath.write(output, dependencies);
        Path implementation = loaderLocation(GuestSystemClassLoader.class.getProtectionDomain().getCodeSource()
            .getLocation());
        return launch(mainClass, implementation, preview, descriptor);
    } // launch

    /**
     * Converts the loader implementation's code-source URL to its launch path.
     * @param location Loader code-source location.
     * @return Filesystem location.
     * @throws IOException If the code-source URL cannot be represented as a URI.
     */
    static Path loaderLocation(URL location) throws IOException {
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid guest loader location", failure);
        } // try
    } // loaderLocation

    /**
     * Configures the connector, optionally selecting the prepared-path system loader.
     *
     * @param mainClass Guest entry point.
     * @param classPath Initial JVM class path.
     * @param preview Whether preview bytecode is enabled.
     * @param descriptor Prepared-path descriptor, or null for the existing JVM loader.
     * @return Suspended guest VM.
     * @throws IOException On process creation failure.
     * @throws IllegalConnectorArgumentsException On invalid JDI arguments.
     * @throws VMStartException On guest startup failure.
     */
    private static VirtualMachine launch(String mainClass, Path classPath, boolean preview, Path descriptor)
            throws IOException, IllegalConnectorArgumentsException, VMStartException {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        String path = classPath.toAbsolutePath().toString();
        // JDI removes its quote delimiters before passing the argument to the JVM.
        // Private-use characters cannot collide with JDI's generated ASCII options.
        char quote = '\uE000';
        String literals = path + mainClass + System.getProperty("java.home") + descriptor;
        while (literals.indexOf(quote) >= 0) {
            quote++;
        } // while
        arguments.get("quote").setValue(String.valueOf(quote));
        arguments.get("main").setValue(mainClass);
        String options = "-Djava.awt.headless=true -classpath " + quote + path + quote;
        if (descriptor != null) {
            options += " -Xshare:off -Djava.system.class.loader=" + GuestSystemClassLoader.class.getName()
                + " -D" + GuestSystemClassLoader.DESCRIPTOR_PROPERTY + "=" + quote + descriptor + quote;
        } // if
        if (preview) {
            options = "--enable-preview " + options;
        } // if
        arguments.get("options").setValue(options);
        return connector.launch(arguments);
    } // launch

} // GuestLauncher
