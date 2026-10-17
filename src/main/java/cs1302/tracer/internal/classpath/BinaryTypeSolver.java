package cs1302.tracer.internal.classpath;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javassist.ClassPath;
import javassist.ClassPool;
import javassist.NotFoundException;
import org.apiguardian.api.API;

/** Job-owned bytecode type resolution over the same version-selected directories used by javac. */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class BinaryTypeSolver implements TypeSolver {

    private final BinaryLookup lookup;
    private final ClassPool pool = new ClassPool(false);
    private TypeSolver parent;

    /**
     * Creates a bytecode solver without loading dependency classes into the tracer JVM.
     * @param roots Compiled output followed by version-selected dependency directories.
     */
    public BinaryTypeSolver(List<Path> roots) {
        this.lookup = new BinaryLookup(roots);
        this.pool.appendClassPath(this.lookup);
    } // BinaryTypeSolver

    /** Reads bytecode through filesystem and platform-resource boundaries without loading guest classes. */
    static final class BinaryLookup implements ClassPath {

        private final List<Path> roots;

        /**
         * Retains an immutable lookup order.
         * @param roots Compiled output and prepared dependency views.
         */
        BinaryLookup(List<Path> roots) {
            this.roots = List.copyOf(roots);
        } // BinaryLookup

        /** {@inheritDoc} */
        @Override
        public InputStream openClassfile(String classname) throws NotFoundException {
            InputStream platform = ClassLoader.getPlatformClassLoader()
                    .getResourceAsStream(classname.replace('.', '/') + ".class");
            if (platform != null) {
                return platform;
            } // if
            Path path = findFile(classname);
            try {
                return path == null ? null : Files.newInputStream(path);
            } catch (IOException failure) {
                throw new NotFoundException(classname, failure);
            } // try
        } // openClassfile

        /** {@inheritDoc} */
        @Override
        public URL find(String classname) {
            URL platform = ClassLoader.getPlatformClassLoader()
                    .getResource(classname.replace('.', '/') + ".class");
            if (platform != null) {
                return platform;
            } // if
            Path path = findFile(classname);
            try {
                return path == null ? null : path.toUri().toURL();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } // try
        } // find

        /**
         * Finds a class file without initializing a dependency class.
         * @param name Binary class name.
         * @return First matching file, or null.
         */
        Path findFile(String name) {
            String relative = name.replace('.', '/') + ".class";
            for (Path root : this.roots) {
                Path candidate = root.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                } // if
            } // for
            return null;
        } // findFile

    } // BinaryLookup

    /** {@inheritDoc} */
    @Override
    public TypeSolver getParent() {
        return this.parent;
    } // getParent

    /** {@inheritDoc} */
    @Override
    public void setParent(TypeSolver parent) {
        Objects.requireNonNull(parent, "parent");
        if (this.parent != null || parent == this) {
            throw new IllegalStateException("A binary type solver must have one distinct parent");
        } // if
        this.parent = parent;
    } // setParent

    /** {@inheritDoc} */
    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        String binary = name;
        while (true) {
            if (this.lookup.findFile(binary) != null) {
                try {
                    return SymbolReference.solved(JavassistFactory.toTypeDeclaration(this.pool.get(binary),
                            getRoot()));
                } catch (NotFoundException failure) {
                    throw new IllegalStateException("Unable to read dependency class " + binary, failure);
                } // try
            } // if
            int dot = binary.lastIndexOf('.');
            if (dot < 0) {
                return SymbolReference.unsolved();
            } // if
            binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1);
        } // while
    } // tryToSolveType

    /** Class-path dependencies belong to the unnamed module, not a module-path lookup. */
    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String moduleName,
            String name) {
        return SymbolReference.unsolved();
    } // tryToSolveTypeInModule

} // BinaryTypeSolver
