package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedLambdaConstraintType;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Precomputes and caches immutable source-derived AST indexes, type resolution hierarchies, and lambda analysis for the
 * lifetime of a tracing session to avoid per-snapshot re-indexing.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class SourceAnalysis {

    /** A simple JavaParser object so we don't have to make a new one every time. */
    private static final JavaParser SIMPLE_JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));

    /**
     * An assignment or declaration of a lambda to a variable with line information.
     *
     * @param variableName Variable name.
     * @param lineNumber Line number.
     * @param lambdaImplementation Lambda method implementation text.
     */
    private record LambdaAssignment(String variableName, int lineNumber,
            String lambdaImplementation) {} // LambdaAssignment

    private final List<CompilationUnit> parsedSources;
    private final AstTypeResolver astTypeResolver;
    private final Map<String, List<LambdaAssignment>> lambdaMethodAssignments;
    private final Map<String, Set<String>> finalMethodVariables;
    private final Map<String, Optional<ClassOrInterfaceDeclaration>> classDeclarations;
    private final Map<String, String> staticLambdaImplementations;

    /**
     * Constructs a SourceAnalysis instance holding precomputed AST indexes.
     *
     * @param parsedSources The parsed compilation units.
     * @param astTypeResolver Precomputed type resolver.
     * @param lambdaMethodAssignments Map of method signatures to lambda assignments.
     * @param finalMethodVariables Map of method signatures to final variable names.
     * @param classDeclarations Map of class FQNs to class declarations.
     * @param staticLambdaImplementations Precomputed static lambda implementation text map.
     */
    private SourceAnalysis(List<CompilationUnit> parsedSources, AstTypeResolver astTypeResolver,
            Map<String, List<LambdaAssignment>> lambdaMethodAssignments,
            Map<String, Set<String>> finalMethodVariables,
            Map<String, Optional<ClassOrInterfaceDeclaration>> classDeclarations,
            Map<String, String> staticLambdaImplementations) {
        this.parsedSources = Collections.unmodifiableList(new ArrayList<>(parsedSources));
        this.astTypeResolver = astTypeResolver;
        Map<String, List<LambdaAssignment>> unmodifiableLambdaMap = new HashMap<>();
        lambdaMethodAssignments
                .forEach((k, v) -> unmodifiableLambdaMap.put(k, Collections.unmodifiableList(new ArrayList<>(v))));
        this.lambdaMethodAssignments = Collections.unmodifiableMap(unmodifiableLambdaMap);
        Map<String, Set<String>> unmodifiableFinalMap = new HashMap<>();
        finalMethodVariables
                .forEach((k, v) -> unmodifiableFinalMap.put(k, Collections.unmodifiableSet(new HashSet<>(v))));
        this.finalMethodVariables = Collections.unmodifiableMap(unmodifiableFinalMap);
        this.classDeclarations = Collections.unmodifiableMap(classDeclarations);
        this.staticLambdaImplementations = Collections.unmodifiableMap(new HashMap<>(staticLambdaImplementations));
    } // SourceAnalysis

    /**
     * Builds and indexes source information from a list of compilation units.
     *
     * @param parsedSources List of parsed CompilationUnits.
     * @return SourceAnalysis instance containing pre-indexed AST metadata.
     */
    private static final SourceAnalysis EMPTY = new SourceAnalysis(List.of(), new AstTypeResolver(List.of()), Map.of(),
            Map.of(), Map.of(), Map.of());

    /**
     * Returns an empty SourceAnalysis instance.
     *
     * @return Empty SourceAnalysis.
     */
    public static SourceAnalysis empty() {
        return EMPTY;
    } // empty

    /**
     * Builds and indexes source information from a list of compilation units.
     *
     * @param parsedSources List of parsed CompilationUnits.
     * @return SourceAnalysis instance containing pre-indexed AST metadata.
     */
    public static SourceAnalysis from(List<CompilationUnit> parsedSources) {
        if (parsedSources == null || parsedSources.isEmpty()) {
            return empty();
        } // if

        List<CompilationUnit> nonNull = parsedSources.stream().filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return empty();
        } // if

        AstTypeResolver resolver = new AstTypeResolver(nonNull);
        Map<String, List<LambdaAssignment>> lambdaMap = new HashMap<>();
        Map<String, Set<String>> finalMap = new HashMap<>();
        buildLambdaAndFinalMaps(nonNull, lambdaMap, finalMap);

        Map<String, Optional<ClassOrInterfaceDeclaration>> classDecls = new HashMap<>();
        Map<String, String> staticLambdas = new HashMap<>();

        for (CompilationUnit cu : nonNull) {
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fqn = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
                String simpleName = decl.getNameAsString();
                classDecls.put(fqn, Optional.of(decl));
                classDecls.putIfAbsent(simpleName, Optional.of(decl));

                for (FieldDeclaration fieldDecl : decl.getFields()) {
                    if (!fieldDecl.isStatic()) {
                        continue;
                    } // if
                    for (VariableDeclarator vd : fieldDecl.getVariables()) {
                        vd.getInitializer().filter(Expression::isLambdaExpr).map(Expression::asLambdaExpr)
                                .flatMap(lambda -> {
                                    try {
                                        return tryImplementLambdaSam(lambda);
                                    } catch (RuntimeException e) {
                                        return Optional.empty();
                                    } // try
                                }).ifPresent(impl -> {
                                    String name = vd.getNameAsString();
                                    staticLambdas.put(fqn + "#" + name, impl);
                                    staticLambdas.putIfAbsent(simpleName + "#" + name, impl);
                                });
                    } // for
                } // for
            } // for
        } // for

        return new SourceAnalysis(nonNull, resolver, lambdaMap, finalMap, classDecls, staticLambdas);
    } // from

    /**
     * Returns the underlying parsed compilation units.
     *
     * @return List of compilation units.
     */
    public List<CompilationUnit> parsedSources() {
        return parsedSources;
    } // parsedSources

    /**
     * Returns the precomputed AstTypeResolver instance.
     *
     * @return AstTypeResolver instance.
     */
    public AstTypeResolver astTypeResolver() {
        return astTypeResolver;
    } // astTypeResolver

    /**
     * Resolves source observations for a captured method and line.
     *
     * @param declaringClass Runtime declaring class name.
     * @param methodName Runtime method name.
     * @param argumentTypes Runtime argument type names.
     * @param line Captured source line.
     * @return Source observations, with empty evidence for unmatched methods.
     */
    public Observations observations(String declaringClass, String methodName, List<String> argumentTypes, int line) {
        String key = String.format("%s.%s(%s)", declaringClass, methodName, String.join(",", argumentTypes));
        return new Observations(this.finalMethodVariables.getOrDefault(key, Set.of()),
                this.lambdaMethodAssignments.getOrDefault(key, List.of()), line);
    } // observations

    /** Source observations selected for one captured method and line. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public static final class Observations {
        private final Set<String> finals;
        private final List<LambdaAssignment> lambdas;
        private final int line;

        /**
         * Selects immutable indexed evidence at a source line.
         *
         * @param finals Final variable names.
         * @param lambdas Ordered lambda assignments.
         * @param line Captured line.
         */
        private Observations(Set<String> finals, List<LambdaAssignment> lambdas, int line) {
            this.finals = finals;
            this.lambdas = lambdas;
            this.line = line;
        } // Observations

        /**
         * Reports whether source evidence marks a variable final.
         *
         * @param variable Variable name.
         * @return Whether the variable is final.
         */
        public boolean isFinal(String variable) {
            return this.finals.contains(variable);
        } // isFinal

        /**
         * Selects lambda text, preserving the first-assignment fallback.
         *
         * @param variable Variable name.
         * @return Reconstructed implementation, if supported by source evidence.
         */
        public Optional<String> lambdaImplementation(String variable) {
            return findLambdaImplementation(this.lambdas, variable, this.line);
        } // lambdaImplementation
    } // Observations

    /**
     * Finds the cached ClassOrInterfaceDeclaration for a class name or FQN.
     *
     * @param className Class name or fully-qualified name.
     * @return Optional containing the declaration if found.
     */
    public Optional<ClassOrInterfaceDeclaration> findClassDeclaration(String className) {
        if (className == null) {
            return Optional.empty();
        } // if
        return classDeclarations.getOrDefault(className, Optional.empty());
    } // findClassDeclaration

    /**
     * Finds the cached static lambda implementation text for a static field.
     *
     * @param className Class name.
     * @param fieldName Field name.
     * @return Optional containing lambda implementation text if present.
     */
    public Optional<String> findStaticLambdaImplementation(String className, String fieldName) {
        if (className == null || fieldName == null) {
            return Optional.empty();
        } // if
        return Optional.ofNullable(staticLambdaImplementations.get(className + "#" + fieldName));
    } // findStaticLambdaImplementation

    /**
     * Convert a lambda expression in the AST into an implementation.
     *
     * @param lambda The lambda expression to convert.
     * @return A string containing a valid method implementation.
     */
    private static Optional<String> tryImplementLambdaSam(LambdaExpr lambda) {
        Optional<MethodUsage> maybeSam = FunctionalInterfaceLogic.getFunctionalMethod(lambda.calculateResolvedType());
        if (maybeSam.isEmpty()) {
            return Optional.empty();
        } // if

        MethodUsage sam = maybeSam.get();
        StringBuilder sb = new StringBuilder();

        String resolvedReturnType = lambda.calculateResolvedType().asReferenceType().getTypeParametersMap().stream()
                .filter(p -> sam.returnType().isTypeVariable())
                .filter(p -> p.a.getName().equals(sam.returnType().asTypeVariable().describe()))
                .map(p -> p.b.describe()).findFirst().orElse(sam.returnType().describe());

        sb.append(resolvedReturnType).append(" ").append(sam.getName());

        sb.append(IntStream.range(0, sam.getDeclaration().getNumberOfParams())
                .mapToObj(i -> formatLambdaParam(lambda, i)).collect(Collectors.joining(", ", "(", ")")));

        if (lambda.getBody() instanceof ExpressionStmt e) {
            sb.append("{\n");
            if (!resolvedReturnType.equals("void")) {
                sb.append("return ");
            } // if
            sb.append(e).append("}");
        } else {
            sb.append(lambda.getBody());
        } // if

        return SIMPLE_JAVA_PARSER.parseMethodDeclaration(sb.toString()).getResult().map(Object::toString);
    } // tryImplementLambdaSam

    /**
     * Formats a lambda parameter with its resolved type and name.
     *
     * @param lambda Lambda expression.
     * @param i Parameter index.
     * @return Formatted parameter string.
     */
    private static String formatLambdaParam(LambdaExpr lambda, int i) {
        String typeDesc = switch (lambda.getParameter(i).resolve().getType()) {
        case ResolvedLambdaConstraintType c -> c.getBound().describe();
        case ResolvedType d -> d.describe();
        }; // switch
        return String.format("%s %s", typeDesc, lambda.getParameter(i).getName());
    } // formatLambdaParam

    /**
     * Builds lambda assignment and final variable maps from parsed compilation units.
     *
     * @param parsedSources List of compilation units.
     * @param lambdaMap Target map for lambda assignments.
     * @param finalMap Target map for final variable names.
     */
    private static void buildLambdaAndFinalMaps(List<CompilationUnit> parsedSources,
            Map<String, List<LambdaAssignment>> lambdaMap, Map<String, Set<String>> finalMap) {
        for (CompilationUnit cu : parsedSources) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                String sig = resolveMethodSignature(m);
                lambdaMap.put(sig, extractMethodLambdaAssignments(m));

                Set<String> finals = m.findAll(VariableDeclarationExpr.class).stream()
                        .filter(v -> v.getModifiers().contains(Modifier.finalModifier()))
                        .map(VariableDeclarationExpr::getVariables).flatMap(Collection::stream)
                        .map(VariableDeclarator::getNameAsString).collect(Collectors.toSet());
                finalMap.put(sig, finals);
            } // for
        } // for
    } // buildLambdaAndFinalMaps

    /**
     * Extracts lambda assignments from a method declaration.
     *
     * @param m MethodDeclaration AST node.
     * @return List of lambda assignments.
     */
    private static List<LambdaAssignment> extractMethodLambdaAssignments(MethodDeclaration m) {
        List<LambdaAssignment> assignments = new ArrayList<>();

        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getInitializer().map(Expression::isLambdaExpr).orElse(false)) {
                try {
                    tryImplementLambdaSam(d.getInitializer().get().asLambdaExpr())
                            .ifPresent(impl -> assignments.add(new LambdaAssignment(d.getNameAsString(),
                                    d.getRange().map(r -> r.begin.line).orElse(0), impl)));
                } catch (RuntimeException e) {
                    // Ignore unresolved local lambdas
                } // try
            } // if
        } // for

        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getValue().isLambdaExpr()) {
                String varName = null;
                if (a.getTarget().isNameExpr()) {
                    varName = a.getTarget().asNameExpr().getNameAsString();
                } else {
                    if (a.getTarget().isFieldAccessExpr()) {
                        varName = a.getTarget().asFieldAccessExpr().getNameAsString();
                    } // if
                } // if
                if (varName != null) {
                    final String finalVarName = varName;
                    try {
                        tryImplementLambdaSam(a.getValue().asLambdaExpr())
                                .ifPresent(impl -> assignments.add(new LambdaAssignment(finalVarName,
                                        a.getRange().map(r -> r.begin.line).orElse(0), impl)));
                    } catch (RuntimeException e) {
                        // Ignore unresolved local lambdas
                    } // try
                } // if
            } // if
        } // for

        assignments.sort(Comparator.comparingInt(LambdaAssignment::lineNumber));
        return assignments;
    } // extractMethodLambdaAssignments

    /**
     * Resolves a method qualified signature string.
     *
     * @param m MethodDeclaration AST node.
     * @return Resolved signature.
     */
    private static String resolveMethodSignature(MethodDeclaration m) {
        try {
            return m.resolve().getQualifiedSignature().replaceAll("\\.\\.\\.", "[]").replaceAll("\\s", "");
        } catch (Throwable t) {
            String params = m.getParameters().stream().map(p -> p.getType().asString())
                    .collect(Collectors.joining(","));
            return m.getNameAsString() + "(" + params + ")";
        } // try
    } // resolveMethodSignature

    /**
     * Finds lambda implementation for a variable.
     *
     * @param assignments List of assignments.
     * @param varName Variable name.
     * @param currentLine Current line.
     * @return Optional containing lambda implementation string.
     */
    private static Optional<String> findLambdaImplementation(List<LambdaAssignment> assignments, String varName,
            int currentLine) {
        return assignments.stream().filter(la -> la.variableName().equals(varName) && la.lineNumber() <= currentLine)
                .reduce((first, second) -> second).map(LambdaAssignment::lambdaImplementation)
                .or(() -> assignments.stream().filter(la -> la.variableName().equals(varName)).findFirst()
                        .map(LambdaAssignment::lambdaImplementation));
    } // findLambdaImplementation

} // SourceAnalysis
