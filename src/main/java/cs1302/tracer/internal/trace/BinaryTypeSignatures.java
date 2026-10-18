package cs1302.tracer.internal.trace;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import cs1302.tracer.internal.execution.NestingException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;

/** Formats raw class-file type evidence before JavaParser can conflate legal binary identifiers. */
final class BinaryTypeSignatures {

    /** Prevents utility construction. */
    private BinaryTypeSignatures() {} // BinaryTypeSignatures

    /**
     * A faithful label and the free variables that require owner bindings before specialization.
     * @param label Declaration label.
     * @param variables Free type-variable identifiers.
     */
    record View(String label, Set<String> variables) {

        /**
         * Retains immutable free-variable evidence.
         * @param label Declaration label.
         * @param variables Free variables.
         */
        View {
            variables = Set.copyOf(variables);
        } // View

    } // View

    /**
     * Reads one field signature or descriptor, retaining no label when evidence is unsupported.
     * @param signature Exact class-file signature or descriptor.
     * @param solver Prepared job's type solver.
     * @return Faithful label and binding requirements, or empty for unavailable evidence.
     */
    static Optional<View> field(String signature, TypeSolver solver) {
        try {
            TypeTraversal.validateText(signature.replace("[", ""));
            return view(SignatureAttribute.toTypeSignature(signature), solver);
        } catch (BadBytecode | RuntimeException ignored) {
            return Optional.empty();
        } // try
    } // field

    /**
     * Formats a parsed type, resolving only exact binary identifiers.
     * @param type Raw declaration type.
     * @param solver Prepared job's type solver.
     * @return Faithful label and binding requirements, or empty for unavailable evidence.
     */
    static Optional<View> view(SignatureAttribute.Type type, TypeSolver solver) {
        return view(type, solver, false);
    } // view

    /**
     * Retains binary raw names for hierarchy composition and owner matching.
     * @param type Raw parent declaration.
     * @param solver Prepared solver.
     * @return Exact identity and free variables, or empty for unavailable evidence.
     */
    static Optional<View> parent(SignatureAttribute.Type type, TypeSolver solver) {
        return view(type, solver, true);
    } // parent

    /**
     * Formats a type for display or identity-based hierarchy composition.
     * @param type Raw declaration.
     * @param solver Prepared solver.
     * @param binaryNames Whether raw names must retain binary identities.
     * @return Faithful view, or empty for unavailable evidence.
     */
    private static Optional<View> view(SignatureAttribute.Type type, TypeSolver solver, boolean binaryNames) {
        try {
            Set<String> variables = new HashSet<>();
            return Optional.of(new View(format(type, solver, variables, 0, binaryNames), variables));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        } // try
    } // view

    /**
     * Preserves class-file structure while validating every reference declaration's identity.
     * @param type Current signature component.
     * @param solver Prepared type solver.
     * @param variables Collected free variables.
     * @param depth Current structural depth.
     * @param binaryNames Whether raw names must retain binary identities.
     * @return Component label.
     */
    private static String format(SignatureAttribute.Type type, TypeSolver solver, Set<String> variables, int depth,
            boolean binaryNames) {
        if (depth > TypeTraversal.MAX_DEPTH) {
            throw new NestingException("type_nesting_limit");
        } // if
        if (type instanceof SignatureAttribute.ArrayType array) {
            return format(array.getComponentType(), solver, variables, depth + array.getDimension(), binaryNames)
                    + "[]".repeat(array.getDimension());
        } // if
        if (type instanceof SignatureAttribute.TypeVariable variable) {
            variables.add(variable.getName());
            return variable.getName();
        } // if
        if (type instanceof SignatureAttribute.ClassType reference) {
            String binary = binaryName(reference);
            ResolvedReferenceTypeDeclaration declaration = solver.solveType(binary);
            if (!binary.equals(AstTypeResolver.binaryName(declaration))) {
                throw new IllegalArgumentException("Unproved binary declaration identity");
            } // if
            String name = binaryNames ? binary : AstTypeResolver.displayName(declaration, solver);
            SignatureAttribute.ClassType owner = reference.getDeclaringClass();
            if (hasArguments(owner)) {
                if (binaryNames || name.equals(binary) || declaration.containerType()
                        .map(AstTypeResolver::binaryName).filter(binaryName(owner)::equals).isEmpty()) {
                    throw new IllegalArgumentException("Unsupported parameterized enclosing identity");
                } // if
                name = format(owner, solver, variables, depth + 1, false) + "." + reference.getName();
            } // if
            SignatureAttribute.TypeArgument[] arguments = reference.getTypeArguments();
            if (arguments == null) {
                return name;
            } // if
            if (arguments.length != declaration.getTypeParameters().size()) {
                throw new IllegalArgumentException("Inconsistent binary type arguments");
            } // if
            List<String> labels = new ArrayList<>();
            for (SignatureAttribute.TypeArgument argument : arguments) {
                if (argument.getType() == null) {
                    labels.add("?");
                } else {
                    String bound = format(argument.getType(), solver, variables, depth + 1, binaryNames);
                    labels.add(switch (argument.getKind()) {
                    case '+' -> "? extends " + bound;
                    case '-' -> "? super " + bound;
                    default -> bound;
                    }); // switch
                } // if
            } // for
            return name + "<" + String.join(", ", labels) + ">";
        } // if
        String primitive = type.toString();
        if ("void".equals(primitive)) {
            throw new IllegalArgumentException("A field cannot have type void");
        } // if
        return primitive;
    } // format

    /**
     * Finds enclosing arguments after the binary-name walk has bounded the complete owner chain.
     * @param type Immediate signature owner, or null for a top-level component.
     * @return Whether any enclosing component carries arguments.
     */
    private static boolean hasArguments(SignatureAttribute.ClassType type) {
        SignatureAttribute.ClassType current = type;
        while (current != null) {
            if (current.getTypeArguments() != null) {
                return true;
            } // if
            current = current.getDeclaringClass();
        } // while
        return false;
    } // hasArguments

    /**
     * Reconstructs binary nesting without changing literal dollars.
     * @param type Class signature component.
     * @return Exact binary name.
     */
    private static String binaryName(SignatureAttribute.ClassType type) {
        ArrayDeque<String> components = new ArrayDeque<>();
        SignatureAttribute.ClassType current = type;
        while (current != null) {
            if (components.size() > TypeTraversal.MAX_DEPTH) {
                throw new NestingException("type_nesting_limit");
            } // if
            components.addFirst(current.getName());
            current = current.getDeclaringClass();
        } // while
        return String.join("$", components);
    } // binaryName

} // BinaryTypeSignatures
