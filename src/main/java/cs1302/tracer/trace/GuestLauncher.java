package cs1302.tracer.trace;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Configures the JDI argument tokenizer for literal guest classpaths. */
final class GuestLauncher {

    /** Prevents instantiation. */
    private GuestLauncher() {} // GuestLauncher

    /**
     * Launches a suspended guest with a single literal classpath entry.
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
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        String path = classPath.toAbsolutePath().toString();
        // JDI removes its quote delimiters before passing the argument to the JVM.
        // Private-use characters cannot collide with JDI's generated ASCII options.
        char quote = '\uE000';
        String literals = path + mainClass + System.getProperty("java.home");
        while (literals.indexOf(quote) >= 0) {
            quote++;
        } // while
        arguments.get("quote").setValue(String.valueOf(quote));
        arguments.get("main").setValue(mainClass);
        String options = "-Djava.awt.headless=true -classpath " + quote + path + quote;
        if (preview) {
            options = "--enable-preview " + options;
        } // if
        arguments.get("options").setValue(options);
        return connector.launch(arguments);
    } // launch
} // GuestLauncher
