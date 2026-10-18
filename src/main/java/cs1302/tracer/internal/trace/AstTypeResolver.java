package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.StaticJavaParser;
import cs1302.tracer.internal.execution.NestingException;
import cs1302.tracer.internal.classpath.BinaryTypeSolver;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;

/**
 * Indexes source type information and lazily reads prepared binary declarations to reconstruct generic type labels
 * during runtime tracing. Declaration metadata and bindings remain scoped to one tracing job.
 *
 * <p>Normative References: <ul> <li>The Java Language Specification (Java SE 21 Edition), &sect;4.5 (Parameterized
 * Types). <li>The Java Language Specification (Java SE 21 Edition), &sect;4.6 (Type Erasure). <li>The Java Language
 * Specification (Java SE 21 Edition), &sect;8.1.2 (Generic Classes and Type Parameters). <li>The Java Language
 * Specification (Java SE 21 Edition), &sect;15.9 (Class Instance Creation Expressions). <li>The Java Virtual Machine
 * Specification (Java SE 21 Edition), &sect;4.7.9 (The Signature Attribute). </ul>
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public class AstTypeResolver {

    /** Prepared source and binary solver retained for invocation and declaration type evidence. */
    public static final com.github.javaparser.ast.DataKey<com.github.javaparser.resolution.TypeSolver> TYPE_SOLVER =
            new com.github.javaparser.ast.DataKey<>() {
            };

    /** Job-owned raw bytecode evidence, without dependency source files or loaded guest classes. */
    public static final com.github.javaparser.ast.DataKey<BinaryTypeSolver> BINARY_TYPES =
            new com.github.javaparser.ast.DataKey<>() {
            };

    private final InvocationTypeResolver invocationTypes;

    /**
     * Returns the source invocation resolver.
     *
     * @return Invocation resolver.
     */
    InvocationTypeResolver invocationTypes() {
        return invocationTypes;
    } // invocationTypes

    /**
     * Metadata for a class's generic type parameters, fields, and declared supertypes.
     *
     * @param classFqn Declaration identity; exact binary name for bytecode metadata.
     * @param typeParameters List of generic type parameter names.
     * @param fieldTypes Map of field names to declared type strings.
     * @param fieldIsFinal Map of field names to final modifiers.
     * @param implementedTypes List of implemented interface type strings.
     * @param extendedType Optional extended superclass type string.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record ClassGenericInfo(String classFqn, List<String> typeParameters, Map<String, String> fieldTypes,
            Map<String, Boolean> fieldIsFinal, List<String> implementedTypes, Optional<String> extendedType) {

        /**
         * Backward-compatible 4-argument constructor.
         *
         * @param classFqn Fully qualified class name.
         * @param typeParameters List of generic type parameter names.
         * @param fieldTypes Map of field names to declared type strings.
         * @param fieldIsFinal Map of field names to final modifiers.
         */
        public ClassGenericInfo(String classFqn, List<String> typeParameters, Map<String, String> fieldTypes,
                Map<String, Boolean> fieldIsFinal) {
            this(classFqn, typeParameters, fieldTypes, fieldIsFinal, Collections.emptyList(), Optional.empty());
        } // ClassGenericInfo

    } // ClassGenericInfo

    /**
     * Metadata for a variable or parameter within a method or constructor.
     *
     * @param name Variable identifier.
     * @param declaredType Declared AST type name.
     * @param startLine Starting source line number.
     * @param endLine Ending source line number.
     * @param isParameter True if this variable is a parameter.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record VariableInfo(
            String name, String declaredType, int startLine, int endLine, boolean isParameter) {} // VariableInfo

    private final List<CompilationUnit> compilationUnits;
    private final Map<String, ClassGenericInfo> classInfoMap = new HashMap<>();
    private final Map<String, Set<String>> fieldParameters = new HashMap<>();
    private final Map<String, Set<String>> parentParameters = new HashMap<>();
    private final Map<String, List<VariableInfo>> methodVariablesMap = new HashMap<>();
    private final Map<String, Map<Integer, String>> allocationSiteMap = new HashMap<>();

    /**
     * Create an {@link AstTypeResolver} from a list of compilation units.
     *
     * @param compilationUnits The compilation units to analyze.
     */
    public AstTypeResolver(List<CompilationUnit> compilationUnits) {
        this.compilationUnits = compilationUnits != null ? compilationUnits : Collections.emptyList();
        invocationTypes = new InvocationTypeResolver(this.compilationUnits);
        indexCompilationUnits();
    } // AstTypeResolver

    /**
     * Create an {@link AstTypeResolver} from a single compilation unit.
     *
     * @param compilationUnit The compilation unit to analyze.
     */
    public AstTypeResolver(CompilationUnit compilationUnit) {
        this(compilationUnit != null ? List.of(compilationUnit) : Collections.emptyList());
    } // AstTypeResolver

    /** Index all provided compilation units. */
    private void indexCompilationUnits() {
        for (CompilationUnit cu : compilationUnits) {
            if (cu != null) {
                for (TypeDeclaration<?> typeDecl : cu.findAll(TypeDeclaration.class)) {
                    indexTypeDeclaration(typeDecl);
                } // for
            } // if
        } // for
    } // indexCompilationUnits

    /**
     * Index a single type declaration.
     *
     * @param typeDecl The type declaration AST node.
     */
    private void indexTypeDeclaration(TypeDeclaration<?> typeDecl) {
        String classFqn = typeDecl.getFullyQualifiedName().orElseGet(typeDecl::getNameAsString);

        List<String> typeParams = new ArrayList<>();
        List<String> implementedTypes = new ArrayList<>();
        Optional<String> extendedType = Optional.empty();

        if (typeDecl instanceof ClassOrInterfaceDeclaration cid) {
            cid.getTypeParameters().forEach(tp -> typeParams.add(tp.getNameAsString()));
            for (ClassOrInterfaceType it : cid.getImplementedTypes()) {
                implementedTypes.add(resolveAstTypeWithParams(it, typeParams));
            } // for
            if (!cid.getExtendedTypes().isEmpty()) {
                extendedType = Optional.of(resolveAstTypeWithParams(cid.getExtendedTypes().get(0), typeParams));
            } // if
        } // if

        Map<String, String> fieldTypes = new HashMap<>();
        Map<String, Boolean> fieldIsFinal = new HashMap<>();
        Set<String> lexicalParameters = new HashSet<>(typeParams);
        Node enclosing = typeDecl.getParentNode().orElse(null);
        while (enclosing != null) {
            if (enclosing instanceof ClassOrInterfaceDeclaration owner) {
                owner.getTypeParameters().forEach(parameter -> lexicalParameters.add(parameter.getNameAsString()));
            } // if
            enclosing = enclosing.getParentNode().orElse(null);
        } // while
        if (typeDecl instanceof ClassOrInterfaceDeclaration named) {
            List<ClassOrInterfaceType> parents = new ArrayList<>(named.getImplementedTypes());
            parents.addAll(named.getExtendedTypes());
            for (ClassOrInterfaceType parent : parents) {
                this.parentParameters.put(classFqn + "#" + resolveAstTypeWithParams(parent, typeParams),
                        sourceParameters(parent, lexicalParameters));
            } // for
        } // if
        for (FieldDeclaration fd : typeDecl.getFields()) {
            boolean isFinal = fd.isFinal();
            for (VariableDeclarator vd : fd.getVariables()) {
                fieldTypes.put(vd.getNameAsString(), resolveAstTypeWithParams(vd.getType(), typeParams));
                fieldIsFinal.put(vd.getNameAsString(), isFinal);
                this.fieldParameters.put(classFqn + "#" + vd.getNameAsString(),
                        sourceParameters(vd.getType(), lexicalParameters));
            } // for
        } // for

        classInfoMap.put(classFqn,
                new ClassGenericInfo(classFqn, typeParams, fieldTypes, fieldIsFinal, implementedTypes, extendedType));

        indexConstructors(classFqn, typeParams, typeDecl);
        indexMethods(classFqn, typeParams, typeDecl);
    } // indexTypeDeclaration

    /**
     * Finds lexical variables used by a source field or direct parent declaration.
     *
     * @param type Declared type syntax.
     * @param lexicalParameters Type variables visible in its enclosing classes.
     * @return Variables requiring evidence before this type can be specialized.
     */
    private static Set<String> sourceParameters(Type type, Set<String> lexicalParameters) {
        Set<String> variables = new HashSet<>();
        for (Type part : TypeTraversal.postorder(type)) {
            if (part instanceof ClassOrInterfaceType named && named.getScope().isEmpty()
                    && lexicalParameters.contains(named.getNameAsString())) {
                variables.add(named.getNameAsString());
            } // if
        } // for
        return variables;
    } // sourceParameters

    /**
     * Index constructors within a type declaration.
     *
     * @param classFqn Class FQN.
     * @param typeParams Class type parameters.
     * @param typeDecl Type declaration AST node.
     */
    private void indexConstructors(String classFqn, List<String> typeParams, TypeDeclaration<?> typeDecl) {
        for (ConstructorDeclaration cd : typeDecl.getConstructors()) {
            String methodKey = makeMethodKey(classFqn, "<init>");
            List<VariableInfo> vars = methodVariablesMap.computeIfAbsent(methodKey, k -> new ArrayList<>());

            List<String> constructorTypeParams = new ArrayList<>(typeParams);
            cd.getTypeParameters().forEach(tp -> constructorTypeParams.add(tp.getNameAsString()));

            int startLine = cd.getRange().map(r -> r.begin.line).orElse(0);
            int endLine = cd.getRange().map(r -> r.end.line).orElse(Integer.MAX_VALUE);

            for (Parameter p : cd.getParameters()) {
                vars.add(new VariableInfo(p.getNameAsString(),
                        resolveAstTypeWithParams(p.getType(), constructorTypeParams), startLine, endLine, true));
            } // for

            for (VariableDeclarator vd : cd.findAll(VariableDeclarator.class)) {
                int vdStart = vd.getRange().map(r -> r.begin.line).orElse(startLine);
                vars.add(new VariableInfo(vd.getNameAsString(),
                        resolveAstTypeWithParams(vd.getType(), constructorTypeParams), vdStart, endLine, false));
            } // for

            indexAllocationSites(classFqn, cd);
        } // for
    } // indexConstructors

    /**
     * Index methods within a type declaration.
     *
     * @param classFqn Class FQN.
     * @param typeParams Class type parameters.
     * @param typeDecl Type declaration AST node.
     */
    private void indexMethods(String classFqn, List<String> typeParams, TypeDeclaration<?> typeDecl) {
        for (MethodDeclaration md : typeDecl.getMethods()) {
            String methodKey = makeMethodKey(classFqn, md.getNameAsString());
            List<VariableInfo> vars = methodVariablesMap.computeIfAbsent(methodKey, k -> new ArrayList<>());

            List<String> methodTypeParams = new ArrayList<>(typeParams);
            md.getTypeParameters().forEach(tp -> methodTypeParams.add(tp.getNameAsString()));

            int startLine = md.getRange().map(r -> r.begin.line).orElse(0);
            int endLine = md.getRange().map(r -> r.end.line).orElse(Integer.MAX_VALUE);

            for (Parameter p : md.getParameters()) {
                vars.add(new VariableInfo(p.getNameAsString(), resolveAstTypeWithParams(p.getType(), methodTypeParams),
                        startLine, endLine, true));
            } // for

            for (VariableDeclarator vd : md.findAll(VariableDeclarator.class)) {
                int vdStart = vd.getRange().map(r -> r.begin.line).orElse(startLine);
                vars.add(new VariableInfo(vd.getNameAsString(),
                        resolveAstTypeWithParams(vd.getType(), methodTypeParams), vdStart, endLine, false));
            } // for

            indexAllocationSites(classFqn, md);
        } // for
    } // indexMethods

    /**
     * Resolve an AST {@link Type}, preserving type parameter names rather than erasing to bounds.
     *
     * @param type The AST type.
     * @param typeParams Known type parameters in the current scope.
     * @return The descriptive type string.
     */
    public static String resolveAstTypeWithParams(Type type, List<String> typeParams) {
        if (type == null) {
            return "java.lang.Object";
        } // if
        Map<Type, String> resolved = new java.util.IdentityHashMap<>();
        for (Type child : TypeTraversal.postorder(type)) {
            resolved.put(child, resolveTypeNode(child, typeParams, resolved));
        } // for
        return resolved.get(type);
    } // resolveAstTypeWithParams

    /**
     * Resolves one type after its children.
     *
     * @param type Current type.
     * @param typeParams In-scope type parameters.
     * @param resolved Already resolved children.
     * @return Descriptive type string.
     */
    private static String resolveTypeNode(Type type, List<String> typeParams, Map<Type, String> resolved) {
        String typeStr = type.asString();
        if (typeParams != null && typeParams.contains(typeStr)) {
            return typeStr;
        } // if
        if (type instanceof ClassOrInterfaceType cit) {
            if (typeParams != null && typeParams.contains(cit.getNameAsString())) {
                return cit.getNameAsString();
            } // if
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                String baseType = cit.getNameWithScope();
                try {
                    baseType = resolvedRawName(cit.resolve());
                } catch (Throwable ignored) {
                    // fallback to simple name with scope
                } // try
                List<String> argTypes = new ArrayList<>();
                for (Type arg : cit.getTypeArguments().get()) {
                    argTypes.add(resolved.get(arg));
                } // for
                return baseType + "<" + String.join(", ", argTypes) + ">";
            } // if
        } else {
            if (type instanceof ArrayType at) {
                return resolved.get(at.getComponentType()) + "[]";
            } // if
        } // if
        return resolveAstType(type);
    } // resolveAstTypeWithParams

    /**
     * Index object allocation sites in a given AST node scope.
     *
     * @param classFqn Declaring class FQN.
     * @param scopeNode Scope AST node.
     */
    private void indexAllocationSites(String classFqn, Node scopeNode) {
        Map<Integer, String> lineAllocations = allocationSiteMap.computeIfAbsent(classFqn, k -> new HashMap<>());

        for (VariableDeclarator vd : scopeNode.findAll(VariableDeclarator.class)) {
            if (vd.getInitializer().isPresent()) {
                int line = vd.getRange().map(r -> r.begin.line).orElse(0);
                if (line > 0) {
                    String typeStr = resolveAstType(vd.getType());
                    if (!typeStr.contains("[")) {
                        lineAllocations.put(line, typeStr);
                    } // if
                } // if
            } // if
        } // for

        for (AssignExpr ae : scopeNode.findAll(AssignExpr.class)) {
            int line = ae.getRange().map(r -> r.begin.line).orElse(0);
            if (line > 0) {
                try {
                    String typeStr = ae.getTarget().calculateResolvedType().describe();
                    if (!typeStr.contains("[")) {
                        lineAllocations.put(line, typeStr);
                    } // if
                } catch (Throwable ignored) {
                    // skip resolution on error
                } // try
            } // if
        } // for

        for (ObjectCreationExpr oce : scopeNode.findAll(ObjectCreationExpr.class)) {
            int line = oce.getRange().map(r -> r.begin.line).orElse(0);
            if (line > 0) {
                String allocType = resolveObjectCreationType(oce);
                if (!allocType.contains("[")) {
                    lineAllocations.put(line, allocType);
                } // if
            } // if
        } // for
    } // indexAllocationSites

    /**
     * Formats a method key from class name and method name.
     *
     * @param classFqn Class FQN.
     * @param methodName Method name.
     * @return Formatted key string.
     */
    private static String makeMethodKey(String classFqn, String methodName) {
        return classFqn + "." + methodName;
    } // makeMethodKey

    /**
     * Safely resolve an AST {@link Type} into a descriptive type string.
     *
     * @param type The AST type.
     * @return The resolved type name or raw AST type string.
     */
    public static String resolveAstType(Type type) {
        if (type == null) {
            return "java.lang.Object";
        } // if
        TypeTraversal.postorder(type);
        try {
            if (type instanceof ClassOrInterfaceType named && named.getTypeArguments().isEmpty()) {
                return resolvedRawName(type.resolve());
            } // if
            return type.resolve().describe();
        } catch (Throwable t) {
            return type.asString();
        } // try
    } // resolveAstType

    /**
     * Formats a resolved raw source reference without relying on dollar-normalizing solver descriptions.
     *
     * @param type Resolved source type.
     * @return Proven declaration identity, or the solver's raw description when no declaration is available.
     */
    private static String resolvedRawName(ResolvedType type) {
        String fallback = extractRawTypeName(type.describe());
        if (type.isReferenceType()) {
            return type.asReferenceType().getTypeDeclaration()
                    .map(declaration -> canonicalName(declaration).orElseGet(() -> binaryName(declaration)))
                    .orElse(fallback);
        } // if
        return fallback;
    } // resolvedRawName

    /**
     * Extracts the raw type name by stripping any generic type arguments.
     *
     * @param typeString The type string.
     * @return Raw type name without angle brackets.
     */
    public static String extractRawTypeName(String typeString) {
        if (typeString == null) {
            return "";
        } // if
        int idx = typeString.indexOf('<');
        if (idx != -1) {
            return typeString.substring(0, idx).trim();
        } // if
        return typeString.trim();
    } // extractRawTypeName

    /**
     * Normalizes wildcard type arguments to their concrete upper bound or Object.
     *
     * @param arg The type argument.
     * @return Normalized type string.
     */
    public static String normalizeWildcard(String arg) {
        if (arg == null) {
            return "java.lang.Object";
        } // if
        String trimmed = arg.trim();
        if (trimmed.equals("?")) {
            return "java.lang.Object";
        } // if
        if (trimmed.startsWith("? extends ")) {
            return trimmed.substring("? extends ".length()).trim();
        } // if
        if (trimmed.startsWith("? super ")) {
            return trimmed.substring("? super ".length()).trim();
        } // if
        return trimmed;
    } // normalizeWildcard

    /**
     * Compare two raw type names, allowing for suffix matching when one is simple.
     *
     * @param raw1 First raw type name.
     * @param raw2 Second raw type name.
     * @return True if they match exactly or as suffixes.
     */
    public static boolean rawTypeMatches(String raw1, String raw2) {
        if (raw1 == null || raw2 == null) {
            return false;
        } // if
        if (raw1.equals(raw2)) {
            return true;
        } // if
        return raw1.indexOf('.') < 0 && raw2.endsWith("." + raw1)
                || raw2.indexOf('.') < 0 && raw1.endsWith("." + raw2);
    } // rawTypeMatches

    /**
     * Checks if a class is a standard single-parameter collection class.
     *
     * @param classFqn Class FQN.
     * @return True if single-parameter collection.
     */
    private static boolean isStandardSingleParamCollection(String classFqn) {
        return "java.util.ArrayList".equals(classFqn) || "ArrayList".equals(classFqn)
                || "java.util.LinkedList".equals(classFqn) || "LinkedList".equals(classFqn)
                || "java.util.Vector".equals(classFqn) || "Vector".equals(classFqn)
                || "java.util.ArrayDeque".equals(classFqn) || "ArrayDeque".equals(classFqn)
                || "java.util.PriorityQueue".equals(classFqn) || "PriorityQueue".equals(classFqn)
                || "java.util.HashSet".equals(classFqn) || "HashSet".equals(classFqn)
                || "java.util.LinkedHashSet".equals(classFqn) || "LinkedHashSet".equals(classFqn)
                || "java.util.TreeSet".equals(classFqn) || "TreeSet".equals(classFqn);
    } // isStandardSingleParamCollection

    /**
     * Checks if a class is a standard map class.
     *
     * @param classFqn Class FQN.
     * @return True if standard map.
     */
    private static boolean isStandardMap(String classFqn) {
        return "java.util.HashMap".equals(classFqn) || "HashMap".equals(classFqn)
                || "java.util.LinkedHashMap".equals(classFqn) || "LinkedHashMap".equals(classFqn)
                || "java.util.TreeMap".equals(classFqn) || "TreeMap".equals(classFqn)
                || "java.util.Hashtable".equals(classFqn) || "Hashtable".equals(classFqn)
                || "java.util.concurrent.ConcurrentHashMap".equals(classFqn) || "ConcurrentHashMap".equals(classFqn);
    } // isStandardMap

    /**
     * Looks up {@link ClassGenericInfo} by FQN or simple name.
     *
     * @param className Class name or FQN.
     * @return Found info or null.
     */
    private ClassGenericInfo findClassGenericInfo(String className) {
        ClassGenericInfo info = classInfoMap.get(className);
        if (info != null) {
            return info;
        } // if
        ClassGenericInfo binary = loadBinaryClassInfo(className);
        if (binary != null) {
            return binary;
        } // if
        for (Map.Entry<String, ClassGenericInfo> entry : classInfoMap.entrySet()) {
            if (rawTypeMatches(entry.getKey(), className)) {
                return entry.getValue();
            } // if
        } // for
        return null;
    } // findClassGenericInfo

    /**
     * Validates a declared functional view against the implemented interface's exact binary identity.
     *
     * @param className Implemented interface's qualified binary name.
     * @param declaredRaw Declared interface name without type arguments.
     * @return Proven canonical interface name, or empty when identity or its single abstract method is unavailable.
     */
    Optional<String> functionalInterfaceName(String className, String declaredRaw) {
        for (CompilationUnit unit : this.compilationUnits) {
            if (unit == null || !unit.containsData(TYPE_SOLVER)) {
                continue;
            } // if
            try {
                ResolvedReferenceTypeDeclaration declaration = unit.getData(TYPE_SOLVER).solveType(className);
                String binary = binaryName(declaration);
                String canonical = displayName(declaration, unit.getData(TYPE_SOLVER));
                if (className.equals(binary) && (declaredRaw.equals(binary) || declaredRaw.equals(canonical))
                        && declaration.isInterface()
                        && FunctionalInterfaceLogic.getFunctionalMethod(declaration).isPresent()) {
                    return Optional.of(canonical);
                } // if
            } catch (RuntimeException ignored) {
                // Unavailable or unsupported declarations cannot establish a functional view.
            } // try
        } // for
        return Optional.empty();
    } // functionalInterfaceName

    /**
     * Restores a declaration's binary identity while preserving literal dollars in its package and identifiers.
     *
     * @param declaration Resolved source or binary declaration.
     * @return Qualified binary name.
     */
    static String binaryName(ResolvedReferenceTypeDeclaration declaration) {
        String packageName = declaration.getPackageName();
        String className = declaration.getClassName().replace('.', '$');
        return packageName == null || packageName.isEmpty() ? className : packageName + "." + className;
    } // binaryName

    /**
     * Converts only proved enclosing-type boundaries to canonical-name separators.
     *
     * @param declaration Resolved source or binary declaration.
     * @return Canonical identity, or empty for inconsistent enclosing-type metadata.
     */
    static Optional<String> canonicalName(ResolvedReferenceTypeDeclaration declaration) {
        String suffix = "";
        ResolvedReferenceTypeDeclaration current = declaration;
        while (true) {
            String name = binaryName(current);
            Optional<ResolvedReferenceTypeDeclaration> container = current.containerType();
            if (container.isEmpty()) {
                return Optional.of(name + suffix);
            } // if
            String owner = binaryName(container.orElseThrow());
            if (!name.startsWith(owner + "$")) {
                return Optional.empty();
            } // if
            // A proved enclosing name is strictly shorter, so malformed cycles cannot continue.
            suffix = "." + name.substring(owner.length() + 1) + suffix;
            current = container.orElseThrow();
        } // while
    } // canonicalName

    /**
     * Uses canonical display spelling only when it resolves to this exact binary declaration.
     * @param declaration Proven declaration.
     * @param solver Prepared type solver.
     * @return Unambiguous canonical spelling, or the exact binary name.
     */
    static String displayName(ResolvedReferenceTypeDeclaration declaration,
            com.github.javaparser.resolution.TypeSolver solver) {
        String binary = binaryName(declaration);
        String canonical = canonicalName(declaration).orElse(binary);
        try {
            return binary.equals(binaryName(solver.solveType(canonical))) ? canonical : binary;
        } catch (RuntimeException ignored) {
            return binary;
        } // try
    } // displayName

    /**
     * Reads declaration evidence through the prepared job's existing bytecode solver.
     *
     * @param className Runtime binary class name.
     * @return Generic declaration metadata, or null when resolution is unavailable.
     */
    private ClassGenericInfo loadBinaryClassInfo(String className) {
        for (CompilationUnit unit : this.compilationUnits) {
            if (unit == null || !unit.containsData(TYPE_SOLVER)) {
                continue;
            } // if
            try {
                ResolvedReferenceTypeDeclaration declaration = unit.getData(TYPE_SOLVER).solveType(className);
                String binary = binaryName(declaration);
                String qualified = displayName(declaration, unit.getData(TYPE_SOLVER));
                if (!className.equals(binary) && !className.equals(qualified)) {
                    continue;
                } // if
                ClassGenericInfo existing = this.classInfoMap.get(binary);
                if (existing != null) {
                    return existing;
                } // if
                if (!unit.containsData(BINARY_TYPES)) {
                    continue;
                } // if
                Optional<BinaryTypeSolver.DeclarationSignatures> raw =
                        unit.getData(BINARY_TYPES).declarationSignatures(binary);
                if (raw.isEmpty()) {
                    continue;
                } // if
                List<String> parameters = declaration.getTypeParameters().stream().map(p -> p.getName()).toList();
                Map<String, String> fields = new HashMap<>();
                raw.orElseThrow().fields().forEach((name, signature) -> {
                    BinaryTypeSignatures.field(signature, unit.getData(TYPE_SOLVER)).ifPresent(view -> {
                        fields.put(name, view.label());
                        this.fieldParameters.put(binary + "#" + name, view.variables());
                    });
                });
                List<String> interfaces = new ArrayList<>();
                Optional<String> superclass = Optional.empty();
                TypeTraversal.validateText(raw.orElseThrow().hierarchy().replace("[", ""));
                SignatureAttribute.ClassSignature hierarchy =
                        SignatureAttribute.toClassSignature(raw.orElseThrow().hierarchy());
                Optional<BinaryTypeSignatures.View> parent =
                        BinaryTypeSignatures.parent(hierarchy.getSuperClass(), unit.getData(TYPE_SOLVER));
                if (parent.isPresent()) {
                    BinaryTypeSignatures.View view = parent.orElseThrow();
                    superclass = Optional.of(view.label());
                    this.parentParameters.put(binary + "#" + view.label(), view.variables());
                } // if
                for (SignatureAttribute.ClassType implemented : hierarchy.getInterfaces()) {
                    BinaryTypeSignatures.parent(implemented, unit.getData(TYPE_SOLVER)).ifPresent(view -> {
                        interfaces.add(view.label());
                        this.parentParameters.put(binary + "#" + view.label(), view.variables());
                    });
                } // for
                ClassGenericInfo info = new ClassGenericInfo(binary, parameters, fields, Map.of(), interfaces,
                        superclass);
                this.classInfoMap.put(binary, info);
                return info;
            } catch (RuntimeException | BadBytecode ignored) {
                // Missing or unsupported binary evidence must retain the erased fallback.
            } // try
        } // for
        return null;
    } // loadBinaryClassInfo

    /**
     * Describes one owner along a parameterized class/interface hierarchy.
     *
     * @param concreteType Concrete type with available argument evidence.
     * @param targetRaw Requested ancestor's raw name.
     * @return Composed ancestor type, if a declaration path is available.
     */
    private Optional<String> ancestorType(String concreteType, String targetRaw) {
        ClassGenericInfo target = findClassGenericInfo(targetRaw);
        String targetName = target == null ? targetRaw : target.classFqn();
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(concreteType);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            String raw = extractRawTypeName(current);
            ClassGenericInfo info = findClassGenericInfo(raw);
            String name = info == null ? raw : info.classFqn();
            if (!visited.add(name)) {
                continue;
            } // if
            if (rawTypeMatches(name, targetName)) {
                int arguments = current.indexOf('<');
                return Optional.of(name + (arguments < 0 ? "" : current.substring(arguments)));
            } // if
            if (info == null) {
                continue;
            } // if
            Map<String, String> bindings = getTypeBindings(raw, current);
            List<String> parents = new ArrayList<>(info.implementedTypes());
            info.extendedType().ifPresent(parents::add);
            for (String parent : parents) {
                Set<String> needed = this.parentParameters.getOrDefault(info.classFqn() + "#" + parent, Set.of());
                pending.add(bindings.keySet().containsAll(needed)
                        ? substituteType(parent, bindings) : extractRawTypeName(parent));
            } // for
        } // while
        return Optional.empty();
    } // ancestorType

    /**
     * Resolves a field using its declaring owner rather than the concrete class's field names.
     *
     * @param concreteType Reconstructed runtime type.
     * @param declaringType Owner reported by JDI for this exact field.
     * @param fieldName Declared field name.
     * @return Declared field type when sufficient parameter evidence is available.
     */
    Optional<String> fieldType(String concreteType, String declaringType, String fieldName) {
        ClassGenericInfo owner = findClassGenericInfo(declaringType);
        if (owner == null) {
            return Optional.empty();
        } // if
        String declared = owner.fieldTypes().get(fieldName);
        if (declared == null) {
            return Optional.empty();
        } // if
        Set<String> needed = this.fieldParameters.getOrDefault(owner.classFqn() + "#" + fieldName, Set.of());
        if (needed.isEmpty()) {
            return Optional.of(declared);
        } // if
        Map<String, String> bindings = ancestorType(concreteType, declaringType)
                .map(type -> getTypeBindings(declaringType, type)).orElseGet(Collections::emptyMap);
        if (needed.stream().allMatch(parameter -> bindings.containsKey(parameter)
                && !bindings.get(parameter).startsWith("?"))) {
            return Optional.of(substituteType(declared, bindings));
        } // if
        return Optional.empty();
    } // fieldType

    /**
     * Resolves the concrete subclass's reified type given an interface or superclass type string.
     *
     * @param concreteClassFqn The concrete class FQN.
     * @param interfaceOrSuperTypeString The parameterized interface or superclass type.
     * @return The parameterized concrete type string, or raw class name.
     */
    public String resolveSubclassType(String concreteClassFqn, String interfaceOrSuperTypeString) {
        if (concreteClassFqn == null) {
            return "java.lang.Object";
        } // if
        if (interfaceOrSuperTypeString == null) {
            return concreteClassFqn;
        } // if
        ClassGenericInfo info = findClassGenericInfo(concreteClassFqn);
        if (info == null || info.typeParameters().isEmpty()) {
            return concreteClassFqn;
        } // if

        String targetRaw = extractRawTypeName(interfaceOrSuperTypeString);
        List<String> targetArgs = extractTypeArguments(interfaceOrSuperTypeString);
        if (targetArgs.isEmpty()) {
            return concreteClassFqn;
        } // if

        String symbolic = concreteClassFqn + "<" + String.join(", ", info.typeParameters()) + ">";
        Optional<String> ancestor = ancestorType(symbolic, targetRaw);
        if (ancestor.isPresent()) {
            ClassGenericInfo target = findClassGenericInfo(targetRaw);
            String declaredIdentity = target == null ? interfaceOrSuperTypeString
                    : target.classFqn() + interfaceOrSuperTypeString.substring(interfaceOrSuperTypeString.indexOf('<'));
            TypeTraversal.validateText(ancestor.get());
            TypeTraversal.validateText(declaredIdentity);
            Map<String, String> bindings = new HashMap<>();
            if (matchTypeArguments(ancestor.get(), declaredIdentity, info.typeParameters(), bindings)
                    && !bindings.isEmpty()) {
                return concreteClassFqn + "<" + String.join(", ", info.typeParameters().stream()
                        .map(parameter -> bindings.getOrDefault(parameter, "?")).toList()) + ">";
            } // if
        } // if
        return concreteClassFqn;
    } // resolveSubclassType

    /**
     * Matches a symbolic ancestor against declared arguments without guessing unknown parameters.
     *
     * @param pattern Ancestor type containing concrete-class parameters.
     * @param actual Declared reference type evidence.
     * @param parameters Concrete-class parameter names.
     * @param bindings Consistent parameter evidence accumulated by this match.
     * @return Whether all structural constraints agree.
     */
    private static boolean matchTypeArguments(String pattern, String actual, List<String> parameters,
            Map<String, String> bindings) {
        if (parameters.contains(pattern)) {
            String previous = bindings.putIfAbsent(pattern, actual);
            return previous == null || previous.equals(actual);
        } // if
        if (pattern.endsWith("[]") && actual.endsWith("[]")) {
            return matchTypeArguments(pattern.substring(0, pattern.length() - 2),
                    actual.substring(0, actual.length() - 2), parameters, bindings);
        } // if
        if (!rawTypeMatches(extractRawTypeName(pattern), extractRawTypeName(actual))) {
            return false;
        } // if
        List<String> expected = extractTypeArguments(pattern);
        List<String> supplied = extractTypeArguments(actual);
        if (expected.size() != supplied.size()) {
            return false;
        } // if
        for (int index = 0; index < expected.size(); index++) {
            if (!matchTypeArguments(expected.get(index), supplied.get(index), parameters, bindings)) {
                return false;
            } // if
        } // for
        return true;
    } // matchTypeArguments

    /**
     * Reconciles a concrete runtime class with a declared reference type to produce a fully parameterized concrete type
     * name.
     *
     * @param runtimeClassFqn The concrete runtime class FQN.
     * @param declaredType The declared variable type.
     * @return Reconciled concrete type name.
     */
    public String reconcileRuntimeType(String runtimeClassFqn, String declaredType) {
        if (runtimeClassFqn == null) {
            return (declaredType != null) ? declaredType : "java.lang.Object";
        } // if
        if (declaredType == null || !declaredType.contains("<")) {
            return runtimeClassFqn;
        } // if

        String declaredRaw = extractRawTypeName(declaredType);
        List<String> declaredArgs = extractTypeArguments(declaredType).stream().map(AstTypeResolver::normalizeWildcard)
                .toList();
        if (declaredArgs.isEmpty()) {
            return runtimeClassFqn;
        } // if

        if (rawTypeMatches(runtimeClassFqn, declaredRaw)) {
            return runtimeClassFqn + "<" + String.join(", ", declaredArgs) + ">";
        } // if

        ClassGenericInfo info = findClassGenericInfo(runtimeClassFqn);
        if (info != null) {
            String subclassType = resolveSubclassType(runtimeClassFqn, declaredType);
            if (!subclassType.equals(runtimeClassFqn)) {
                return subclassType;
            } // if
            if (info.typeParameters().isEmpty()) {
                return runtimeClassFqn;
            } // if
        } // if

        if (isStandardSingleParamCollection(runtimeClassFqn)) {
            return runtimeClassFqn + "<" + declaredArgs.get(0) + ">";
        } // if
        if (isStandardMap(runtimeClassFqn) && declaredArgs.size() >= 2) {
            return runtimeClassFqn + "<" + declaredArgs.get(0) + ", " + declaredArgs.get(1) + ">";
        } // if

        return runtimeClassFqn;
    } // reconcileRuntimeType

    /**
     * Safely resolve an {@link ObjectCreationExpr} target type.
     *
     * @param oce The object creation expression.
     * @return The resolved type string or raw type string.
     */
    public String resolveObjectCreationType(ObjectCreationExpr oce) {
        if (oce == null) {
            return "java.lang.Object";
        } // if

        String createdType = resolveAstType(oce.getType());
        if (createdType.endsWith("<>")) {
            createdType = createdType.substring(0, createdType.length() - 2).trim();
        } // if
        if (createdType.contains("<")) {
            return createdType;
        } // if

        if (oce.getTypeArguments().isPresent() && !oce.getTypeArguments().get().isEmpty()) {
            List<String> explicitArgs = oce.getTypeArguments().get().stream().map(AstTypeResolver::resolveAstType)
                    .map(AstTypeResolver::normalizeWildcard).toList();
            return createdType + "<" + String.join(", ", explicitArgs) + ">";
        } // if

        try {
            String resolved = oce.calculateResolvedType().describe();
            if (resolved.contains("<") && !resolved.contains("<?>")) {
                return resolved;
            } // if
        } catch (Throwable ignored) {
            // fall through
        } // try

        String targetType = null;
        if (oce.getParentNode().isPresent()) {
            Node parent = oce.getParentNode().get();
            if (parent instanceof VariableDeclarator vd) {
                targetType = resolveAstType(vd.getType());
            } else {
                if (parent instanceof AssignExpr ae) {
                    try {
                        targetType = ae.getTarget().calculateResolvedType().describe();
                    } catch (Throwable ignored) {
                        // fall through
                    } // try
                } // if
            } // if
        } // if

        if (targetType != null && targetType.contains("<")) {
            return reconcileRuntimeType(createdType, targetType);
        } // if

        return createdType;
    } // resolveObjectCreationType

    /**
     * Get class generic information for a given class FQN.
     *
     * @param classFqn Fully qualified class name.
     * @return Optional containing {@link ClassGenericInfo} if found.
     */
    public Optional<ClassGenericInfo> getClassGenericInfo(String classFqn) {
        return Optional.ofNullable(findClassGenericInfo(classFqn));
    } // getClassGenericInfo

    /**
     * Look up the AST declared type for a variable or parameter in a method at a given line.
     *
     * @param classFqn Declaring class FQN.
     * @param methodName Method name or {@code <init>}.
     * @param variableName Variable name.
     * @param lineNumber Current line number.
     * @return Optional containing the declared AST type string.
     */
    public Optional<String> resolveVariableType(String classFqn, String methodName, String variableName,
            int lineNumber) {
        String methodKey = makeMethodKey(classFqn, methodName);
        List<VariableInfo> vars = methodVariablesMap.get(methodKey);
        if (vars == null || vars.isEmpty()) {
            return Optional.empty();
        } // if

        for (int i = vars.size() - 1; i >= 0; i--) {
            VariableInfo vi = vars.get(i);
            if (vi.name().equals(variableName) && lineNumber >= vi.startLine() && lineNumber <= vi.endLine()) {
                return Optional.of(vi.declaredType());
            } // if
        } // for

        for (VariableInfo vi : vars) {
            if (vi.name().equals(variableName)) {
                return Optional.of(vi.declaredType());
            } // if
        } // for

        return Optional.empty();
    } // resolveVariableType

    /**
     * Resolve a local against the exact active method before using legacy source metadata.
     *
     * @param frame Active frame.
     * @param name Local variable name.
     * @return Declared type, preserving lexical type variables.
     */
    Optional<String> resolveFrameVariableType(com.sun.jdi.StackFrame frame, String name) {
        com.sun.jdi.Method method = frame.location().method();
        Optional<CallableDeclaration<?>> source = invocationTypes.declaration(method);
        if (source.isEmpty()) {
            return resolveVariableType(method.declaringType().name(), method.name(), name,
                    frame.location().lineNumber());
        } // if
        CallableDeclaration<?> declaration = source.get();
        List<String> parameters = new ArrayList<>();
        getClassGenericInfo(method.declaringType().name()).ifPresent(info -> parameters.addAll(info.typeParameters()));
        declaration.getTypeParameters().forEach(tp -> parameters.add(tp.getNameAsString()));
        int line = frame.location().lineNumber();
        VariableDeclarator selected = null;
        for (VariableDeclarator variable : declaration.findAll(VariableDeclarator.class)) {
            if (!variable.getNameAsString().equals(name)
                    || variable.stream(Node.TreeTraversal.PARENTS)
                            .filter(com.github.javaparser.ast.body.CallableDeclaration.class::isInstance).findFirst()
                            .orElse(null) != declaration
                    || variable.getParentNode().filter(VariableDeclarationExpr.class::isInstance).isEmpty()
                    || variable.getRange().map(r -> r.begin.line > line).orElse(true)) {
                continue;
            } // if
            Optional<Node> scope = variable.stream(Node.TreeTraversal.PARENTS)
                    .filter(node -> node instanceof com.github.javaparser.ast.stmt.BlockStmt
                            || node instanceof com.github.javaparser.ast.stmt.ForStmt
                            || node instanceof com.github.javaparser.ast.stmt.ForEachStmt)
                    .findFirst();
            if (scope.flatMap(Node::getRange).map(r -> r.end.line >= line).orElse(false)) {
                selected = variable;
            } // if
        } // for
        if (selected != null) {
            return Optional.of(resolveAstTypeWithParams(selected.getType(), parameters));
        } // if
        return declaration.getParameters().stream().filter(p -> p.getNameAsString().equals(name))
                .map(p -> resolveAstTypeWithParams(p.getType(), parameters) + (p.isVarArgs() ? "[]" : "")).findFirst();
    } // resolveFrameVariableType

    /**
     * Look up an object allocation type at a specific class and line number.
     *
     * @param classFqn Declaring class FQN.
     * @param lineNumber Line number where {@code new} occurred.
     * @return Optional containing the resolved target type.
     */
    public Optional<String> getAllocationType(String classFqn, int lineNumber) {
        Map<Integer, String> lines = allocationSiteMap.get(classFqn);
        if (lines != null && lines.containsKey(lineNumber)) {
            return Optional.of(lines.get(lineNumber));
        } // if
        return Optional.empty();
    } // getAllocationType

    /**
     * Extract type argument bindings by matching a class declaration's type parameters.
     *
     * @param classFqn The fully qualified class name.
     * @param reifiedType The reified generic type string.
     * @return A map of type parameter names to concrete type argument strings.
     */
    public Map<String, String> getTypeBindings(String classFqn, String reifiedType) {
        if (classFqn == null || reifiedType == null) {
            return Collections.emptyMap();
        } // if
        ClassGenericInfo info = findClassGenericInfo(classFqn);
        if (info == null || info.typeParameters().isEmpty()) {
            return Collections.emptyMap();
        } // if

        List<String> typeArgs = extractTypeArguments(reifiedType);
        if (typeArgs.isEmpty() || typeArgs.size() != info.typeParameters().size()) {
            return Collections.emptyMap();
        } // if

        Map<String, String> bindings = new HashMap<>();
        for (int i = 0; i < info.typeParameters().size(); i++) {
            bindings.put(info.typeParameters().get(i), typeArgs.get(i));
        } // for
        return bindings;
    } // getTypeBindings

    /**
     * Extract top-level type arguments from a parameterized type string.
     *
     * @param typeString The type string.
     * @return A list of type argument strings.
     */
    public static List<String> extractTypeArguments(String typeString) {
        if (typeString == null) {
            return Collections.emptyList();
        } // if
        int start = typeString.indexOf('<');
        int end = typeString.lastIndexOf('>');
        if (start == -1 || end <= start) {
            return Collections.emptyList();
        } // if

        String inner = typeString.substring(start + 1, end).trim();
        List<String> args = new ArrayList<>();
        int depth = 0;
        int tokenStart = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            } else {
                if (c == '>') {
                    depth--;
                } else {
                    if (c == ',' && depth == 0) {
                        args.add(inner.substring(tokenStart, i).trim());
                        tokenStart = i + 1;
                    } // if
                } // if
            } // if
        } // for
        if (tokenStart < inner.length()) {
            args.add(inner.substring(tokenStart).trim());
        } // if

        return args;
    } // extractTypeArguments

    /**
     * Substitute type parameters in a declared type string with concrete type arguments.
     *
     * @param typeWithParams The declared type string (e.g. {@code "K"} or {@code "Pair<K, V>"}).
     * @param bindings The map of type parameter names to concrete type arguments.
     * @return The substituted type string.
     */
    public static String substituteType(String typeWithParams, Map<String, String> bindings) {
        if (typeWithParams == null || bindings == null || bindings.isEmpty()) {
            return typeWithParams;
        } // if
        TypeTraversal.validateText(typeWithParams);
        bindings.values().forEach(TypeTraversal::validateText);
        Type substituted;
        try {
            Type parsed = StaticJavaParser.parseType(typeWithParams);
            substituted = substituteType(parsed, bindings);
        } catch (NestingException failure) {
            throw failure;
        } catch (Throwable t) {
            String result = typeWithParams;
            for (Map.Entry<String, String> entry : bindings.entrySet()) {
                result = result.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
                        Matcher.quoteReplacement(entry.getValue()));
            } // for
            return result;
        } // try
        TypeTraversal.postorder(substituted);
        return substituted.asString();
    } // substituteType

    /**
     * Iteratively substitutes type parameters inside an AST {@link Type}.
     *
     * @param type The AST type.
     * @param bindings The map of type parameter names to concrete type arguments.
     * @return Substituted AST Type.
     */
    private static Type substituteType(Type type, Map<String, String> bindings) {
        Map<Type, Type> converted = new java.util.IdentityHashMap<>();
        for (Type child : TypeTraversal.postorder(type)) {
            converted.put(child, substituteNode(child, bindings, converted));
        } // for
        return converted.get(type);
    } // substituteType

    /**
     * Substitutes one bounded type node after its children.
     *
     * @param type Current type.
     * @param bindings Type bindings.
     * @param converted Completed child types.
     * @return Substituted type.
     */
    private static Type substituteNode(Type type, Map<String, String> bindings, Map<Type, Type> converted) {
        if (type instanceof ClassOrInterfaceType cit) {
            String name = cit.getNameAsString();
            if (bindings.containsKey(name) && cit.getScope().isEmpty() && cit.getTypeArguments().isEmpty()) {
                String replacement = bindings.get(name);
                try {
                    return StaticJavaParser.parseType(replacement);
                } catch (Throwable t) {
                    return new ClassOrInterfaceType(null, replacement);
                } // try
            } // if
            ClassOrInterfaceType cloned = cit.clone();
            if (cloned.getTypeArguments().isPresent()) {
                com.github.javaparser.ast.NodeList<Type> newArgs = new com.github.javaparser.ast.NodeList<>();
                for (Type arg : cit.getTypeArguments().get()) {
                    newArgs.add(converted.get(arg));
                } // for
                cloned.setTypeArguments(newArgs);
            } // if
            return cloned;
        } else {
            if (type instanceof ArrayType at) {
                Type newComponent = converted.get(at.getComponentType());
                return new ArrayType(newComponent, at.getOrigin(), at.getAnnotations());
            } else {
                if (type instanceof WildcardType wt) {
                    WildcardType cloned = wt.clone();
                    if (cloned.getExtendedType().isPresent()) {
                        cloned.setExtendedType((ReferenceType) converted.get(wt.getExtendedType().get()));
                    } // if
                    if (cloned.getSuperType().isPresent()) {
                        cloned.setSuperType((ReferenceType) converted.get(wt.getSuperType().get()));
                    } // if
                    return cloned;
                } // if
            } // if
        } // if
        return type.clone();
    } // substituteType

} // AstTypeResolver
