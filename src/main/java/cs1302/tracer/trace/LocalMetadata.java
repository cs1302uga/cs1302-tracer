package cs1302.tracer.trace;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.ArrayList;
import java.util.List;

/** Indexes final declarations by their declaring class, callable, and lexical scope. */
final class LocalMetadata {

    private final List<Declaration> declarations = new ArrayList<>();

    /**
     * A declaration's source lifetime, separate from similarly named locals.
     * @param owner Qualified declaring class and method name.
     * @param name Local name.
     * @param start First line in scope.
     * @param end Last line in scope.
     * @param isFinal Whether the declaration is final.
     */
    private record Declaration(String owner, String name, int start, int end, boolean isFinal) {
    } // Declaration

    /**
     * Prepares local declaration metadata from submitted and compiled sources.
     * @param sources Parsed source units.
     */
    LocalMetadata(List<CompilationUnit> sources) {
        for (CompilationUnit unit : sources) {
            if (unit == null) {
                continue;
            } // if
            for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
                if (variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr expr) {
                    add(variable, variable.getNameAsString(), expr.isFinal());
                } // if
            } // for
            for (Parameter parameter : unit.findAll(Parameter.class)) {
                add(parameter, parameter.getNameAsString(), parameter.isFinal());
            } // for
        } // for
    } // LocalMetadata

    /**
     * Adds a declaration only to its own enclosing callable.
     * @param node Declared variable or parameter.
     * @param name Variable name.
     * @param isFinal Declared final modifier.
     */
    private void add(Node node, String name, boolean isFinal) {
        Node parent = node.getParentNode().orElseThrow();
        Node scope = null;
        while (!(parent instanceof CallableDeclaration<?>)) {
            if (parent instanceof LambdaExpr || parent instanceof TypeDeclaration<?>) {
                return;
            } // if
            if (scope == null && (parent instanceof BlockStmt || parent instanceof ForStmt
                    || parent instanceof ForEachStmt || parent instanceof SwitchEntry)) {
                scope = parent;
            } // if
            if (scope == null && parent instanceof CatchClause clause) {
                scope = clause.getBody();
            } // if
            if (scope == null && parent instanceof TryStmt statement) {
                scope = statement.getTryBlock();
            } // if
            parent = parent.getParentNode().orElseThrow();
        } // while
        CallableDeclaration<?> callable = (CallableDeclaration<?>) parent;
        Node lifetime = scope == null ? callable : scope;
        String owner = ownerName(callable);
        String method = callable instanceof ConstructorDeclaration ? "<init>"
                : callable.getNameAsString();
        declarations.add(new Declaration(owner + "#" + method, name,
                node.getBegin().orElseThrow().line, lifetime.getEnd().orElseThrow().line, isFinal));
    } // add

    /**
     * Finds the nearest declaring type without crossing into another callable.
     * @param node Callable declaration.
     * @return Qualified declaring class, or an empty name if unavailable.
     */
    private static String ownerName(Node node) {
        Node parent = node.getParentNode().orElseThrow();
        return parent instanceof TypeDeclaration<?> type
                ? type.getFullyQualifiedName().orElse("") : "";
    } // ownerName

    /**
     * Resolves a visible local at the current source location.
     * @param className JDI declaring class name.
     * @param methodName JDI callable name.
     * @param name Local variable name.
     * @param line Current source line.
     * @return Whether the applicable declaration is final.
     */
    boolean isFinal(String className, String methodName, String name, int line) {
        String owner = className.replace('$', '.') + "#" + methodName;
        return declarations.stream().filter(d -> d.owner().equals(owner) && d.name().equals(name)
                && d.start() <= line && line <= d.end())
                .max(java.util.Comparator.comparingInt(Declaration::start))
                .map(Declaration::isFinal).orElse(false);
    } // isFinal
} // LocalMetadata
