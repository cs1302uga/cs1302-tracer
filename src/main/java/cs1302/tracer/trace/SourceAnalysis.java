package cs1302.tracer.trace;

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

/**
 * Precomputes and caches immutable source-derived AST indexes, type resolution hierarchies,
 * and lambda analysis for the lifetime of a tracing session to avoid per-snapshot re-indexing.
 */
public final class SourceAnalysis {

    private final List<CompilationUnit> parsedSources;
    private final AstTypeResolver astTypeResolver;
    private final Map<String, List<DebugTraceHelper.LambdaAssignment>> lambdaMethodAssignments;
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
    private SourceAnalysis(
            List<CompilationUnit> parsedSources,
            AstTypeResolver astTypeResolver,
            Map<String, List<DebugTraceHelper.LambdaAssignment>> lambdaMethodAssignments,
            Map<String, Set<String>> finalMethodVariables,
            Map<String, Optional<ClassOrInterfaceDeclaration>> classDeclarations,
            Map<String, String> staticLambdaImplementations) {
        this.parsedSources = Collections.unmodifiableList(new ArrayList<>(parsedSources));
        this.astTypeResolver = astTypeResolver;
        Map<String, List<DebugTraceHelper.LambdaAssignment>> unmodifiableLambdaMap =
                new HashMap<>();
        lambdaMethodAssignments.forEach((k, v) ->
                unmodifiableLambdaMap.put(k, Collections.unmodifiableList(new ArrayList<>(v))));
        this.lambdaMethodAssignments = Collections.unmodifiableMap(unmodifiableLambdaMap);
        Map<String, Set<String>> unmodifiableFinalMap = new HashMap<>();
        finalMethodVariables.forEach((k, v) ->
                unmodifiableFinalMap.put(k, Collections.unmodifiableSet(new HashSet<>(v))));
        this.finalMethodVariables = Collections.unmodifiableMap(unmodifiableFinalMap);
        this.classDeclarations = Collections.unmodifiableMap(classDeclarations);
        this.staticLambdaImplementations = Collections.unmodifiableMap(
                new HashMap<>(staticLambdaImplementations));
    } // SourceAnalysis

    /**
     * Builds and indexes source information from a list of compilation units.
     *
     * @param parsedSources List of parsed CompilationUnits.
     * @return SourceAnalysis instance containing pre-indexed AST metadata.
     */
    private static final SourceAnalysis EMPTY = new SourceAnalysis(
            List.of(),
            new AstTypeResolver(List.of()),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

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

        List<CompilationUnit> nonNull = parsedSources.stream()
                .filter(Objects::nonNull)
                .toList();
        if (nonNull.isEmpty()) {
            return empty();
        } // if

        AstTypeResolver resolver = new AstTypeResolver(nonNull);
        Map<String, List<DebugTraceHelper.LambdaAssignment>> lambdaMap = new HashMap<>();
        Map<String, Set<String>> finalMap = new HashMap<>();
        DebugTraceHelper.buildLambdaAndFinalMaps(nonNull, lambdaMap, finalMap);

        Map<String, Optional<ClassOrInterfaceDeclaration>> classDecls = new HashMap<>();
        Map<String, String> staticLambdas = new HashMap<>();

        for (CompilationUnit cu : nonNull) {
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fqn = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
                String simpleName = decl.getNameAsString();
                classDecls.put(fqn, Optional.of(decl));
                classDecls.putIfAbsent(simpleName, Optional.of(decl));

                for (FieldDeclaration fieldDecl : decl.getFields()) {
                    for (VariableDeclarator vd : fieldDecl.getVariables()) {
                        vd.getInitializer()
                                .filter(Expression::isLambdaExpr)
                                .map(Expression::asLambdaExpr)
                                .flatMap(DebugTraceHelper::tryImplementLambdaSam)
                                .ifPresent(impl -> {
                                    String name = vd.getNameAsString();
                                    staticLambdas.put(fqn + "#" + name, impl);
                                    staticLambdas.putIfAbsent(simpleName + "#" + name, impl);
                                });
                    } // for
                } // for
            } // for
        } // for

        return new SourceAnalysis(
                nonNull,
                resolver,
                lambdaMap,
                finalMap,
                classDecls,
                staticLambdas);
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
     * Returns the map of method signatures to lambda assignments.
     *
     * @return Lambda method assignments map.
     */
    public Map<String, List<DebugTraceHelper.LambdaAssignment>> lambdaMethodAssignments() {
        return lambdaMethodAssignments;
    } // lambdaMethodAssignments

    /**
     * Returns the map of method signatures to final local variable names.
     *
     * @return Final method variables map.
     */
    public Map<String, Set<String>> finalMethodVariables() {
        return finalMethodVariables;
    } // finalMethodVariables

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
} // SourceAnalysis
