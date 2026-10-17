package cs1302.tracer.internal;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.List;
import java.util.stream.Collectors;
import org.apiguardian.api.API;

/**
 * Source filename inference shared by bundle construction and undelimited source preparation.
 * The two entry points preserve their distinct declaration-selection rules; neither compiles code.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class SourceNames {

    /** Prevents instantiation. */
    private SourceNames() {} // SourceNames

    /**
     * Infers a package-relative bundle path from one public type, or from the sole top-level type.
     * A fresh parser uses the same language level as source preparation. Parsed nodes are not retained.
     *
     * @param source One file's Java source text, without bundle delimiters.
     * @return The inferred path with forward slashes and a {@code .java} suffix.
     * @throws IllegalArgumentException If the source cannot be parsed or its filename is ambiguous.
     */
    public static String inferPath(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT));
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful()) {
            throw new IllegalArgumentException("Cannot infer source path: " + parsed.getProblems());
        } // if
        CompilationUnit unit = parsed.getResult().orElseThrow();
        List<TypeDeclaration<?>> publicTypes = publicTypes(unit);
        if (publicTypes.size() == 1) {
            return binaryName(unit, publicTypes.getFirst()).replace('.', '/') + ".java";
        } // if
        if (publicTypes.isEmpty() && unit.getTypes().size() == 1) {
            return binaryName(unit, unit.getType(0)).replace('.', '/') + ".java";
        } // if
        throw new IllegalArgumentException("Cannot infer source path: expected one public top-level type "
                + "or a sole top-level type; found " + publicTypes.size() + " public types among "
                + unit.getTypes().size() + " top-level types. Supply an explicit source path.");
    } // inferPath

    /**
     * Infers the binary name required by the existing undelimited-input contract.
     *
     * @param unit Parsed source file.
     * @return The binary name of its single public top-level type.
     * @throws IllegalArgumentException If the source has zero or multiple public top-level types.
     */
    static String requirePublicBinaryName(CompilationUnit unit) {
        List<TypeDeclaration<?>> declarations = publicTypes(unit);
        if (declarations.size() != 1) {
            String lines = declarations.stream().filter(type -> type.getBegin().isPresent())
                    .map(type -> Integer.toString(type.getBegin().get().line))
                    .collect(Collectors.joining(", ", "[", "]"));
            throw new IllegalArgumentException(String.format(
                    "Java source code must have exactly one public top-level type "
                            + "declaration. Found %d such declarations on lines %s.",
                    declarations.size(), lines));
        } // if
        return binaryName(unit, declarations.getFirst());
    } // requirePublicBinaryName

    /**
     * Selects public top-level type declarations in source order.
     *
     * @param unit Parsed source file.
     * @return Public top-level type declarations.
     */
    private static List<TypeDeclaration<?>> publicTypes(CompilationUnit unit) {
        return unit.getTypes().stream().filter(TypeDeclaration::isPublic).toList();
    } // publicTypes

    /**
     * Combines a top-level type's package and simple name.
     *
     * @param unit Parsed source file.
     * @param type Selected top-level type.
     * @return The package-qualified binary name.
     */
    private static String binaryName(CompilationUnit unit, TypeDeclaration<?> type) {
        return unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + type.getNameAsString();
    } // binaryName

} // SourceNames
