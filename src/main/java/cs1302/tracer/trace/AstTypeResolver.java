package cs1302.tracer.trace;

import com.github.javaparser.StaticJavaParser;
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
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and indexes static type information from JavaParser ASTs to support generic type
 * reification during runtime tracing.
 *
 * <p>Normative References:
 * <ul>
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;4.5 (Parameterized Types).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;4.6 (Type Erasure).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;8.1.2
 *       (Generic Classes and Type Parameters).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;15.9
 *       (Class Instance Creation Expressions).
 *   <li>The Java Virtual Machine Specification (Java SE 21 Edition), &sect;4.7.9
 *       (The Signature Attribute).
 * </ul>
 */
public class AstTypeResolver {

    /**
     * Metadata for a class's generic type parameters, fields, and constructors.
     *
     * @param classFqn Fully qualified class name.
     * @param typeParameters List of generic type parameter names.
     * @param fieldTypes Map of field names to declared type strings.
     * @param fieldIsFinal Map of field names to final modifiers.
     * @param implementedTypes List of implemented interface type strings.
     * @param extendedType Optional extended superclass type string.
     */
    public record ClassGenericInfo(
            String classFqn,
            List<String> typeParameters,
            Map<String, String> fieldTypes,
            Map<String, Boolean> fieldIsFinal,
            List<String> implementedTypes,
            Optional<String> extendedType) {

        /**
         * Backward-compatible 4-argument constructor.
         *
         * @param classFqn Fully qualified class name.
         * @param typeParameters List of generic type parameter names.
         * @param fieldTypes Map of field names to declared type strings.
         * @param fieldIsFinal Map of field names to final modifiers.
         */
        public ClassGenericInfo(
                String classFqn,
                List<String> typeParameters,
                Map<String, String> fieldTypes,
                Map<String, Boolean> fieldIsFinal) {
            this(
                    classFqn,
                    typeParameters,
                    fieldTypes,
                    fieldIsFinal,
                    Collections.emptyList(),
                    Optional.empty());
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
    public record VariableInfo(
            String name,
            String declaredType,
            int startLine,
            int endLine,
            boolean isParameter) {} // VariableInfo

    private final List<CompilationUnit> compilationUnits;
    private final Map<String, ClassGenericInfo> classInfoMap = new HashMap<>();
    private final Map<String, List<VariableInfo>> methodVariablesMap = new HashMap<>();
    private final Map<String, Map<Integer, String>> allocationSiteMap = new HashMap<>();

    /**
     * Create an {@link AstTypeResolver} from a list of compilation units.
     *
     * @param compilationUnits The compilation units to analyze.
     */
    public AstTypeResolver(List<CompilationUnit> compilationUnits) {
        this.compilationUnits =
                compilationUnits != null ? compilationUnits : Collections.emptyList();
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
        String classFqn =
                typeDecl.getFullyQualifiedName().orElseGet(typeDecl::getNameAsString);

        List<String> typeParams = new ArrayList<>();
        List<String> implementedTypes = new ArrayList<>();
        Optional<String> extendedType = Optional.empty();

        if (typeDecl instanceof ClassOrInterfaceDeclaration cid) {
            cid.getTypeParameters().forEach(tp -> typeParams.add(tp.getNameAsString()));
            for (ClassOrInterfaceType it : cid.getImplementedTypes()) {
                implementedTypes.add(resolveAstTypeWithParams(it, typeParams));
            } // for
            if (!cid.getExtendedTypes().isEmpty()) {
                extendedType = Optional.of(
                        resolveAstTypeWithParams(cid.getExtendedTypes().get(0), typeParams));
            } // if
        } // if

        Map<String, String> fieldTypes = new HashMap<>();
        Map<String, Boolean> fieldIsFinal = new HashMap<>();
        for (FieldDeclaration fd : typeDecl.getFields()) {
            boolean isFinal = fd.isFinal();
            for (VariableDeclarator vd : fd.getVariables()) {
                fieldTypes.put(
                        vd.getNameAsString(),
                        resolveAstTypeWithParams(vd.getType(), typeParams));
                fieldIsFinal.put(vd.getNameAsString(), isFinal);
            } // for
        } // for

        classInfoMap.put(
                classFqn,
                new ClassGenericInfo(
                        classFqn,
                        typeParams,
                        fieldTypes,
                        fieldIsFinal,
                        implementedTypes,
                        extendedType));

        indexConstructors(classFqn, typeParams, typeDecl);
        indexMethods(classFqn, typeParams, typeDecl);
    } // indexTypeDeclaration

    /**
     * Index constructors within a type declaration.
     *
     * @param classFqn Class FQN.
     * @param typeParams Class type parameters.
     * @param typeDecl Type declaration AST node.
     */
    private void indexConstructors(
            String classFqn, List<String> typeParams, TypeDeclaration<?> typeDecl) {
        for (ConstructorDeclaration cd : typeDecl.getConstructors()) {
            String methodKey = makeMethodKey(classFqn, "<init>");
            List<VariableInfo> vars =
                    methodVariablesMap.computeIfAbsent(methodKey, k -> new ArrayList<>());

            List<String> constructorTypeParams = new ArrayList<>(typeParams);
            cd.getTypeParameters().forEach(tp -> constructorTypeParams.add(tp.getNameAsString()));

            int startLine = cd.getRange().map(r -> r.begin.line).orElse(0);
            int endLine = cd.getRange().map(r -> r.end.line).orElse(Integer.MAX_VALUE);

            for (Parameter p : cd.getParameters()) {
                vars.add(new VariableInfo(
                        p.getNameAsString(),
                        resolveAstTypeWithParams(p.getType(), constructorTypeParams),
                        startLine,
                        endLine,
                        true));
            } // for

            for (VariableDeclarator vd : cd.findAll(VariableDeclarator.class)) {
                int vdStart = vd.getRange().map(r -> r.begin.line).orElse(startLine);
                vars.add(new VariableInfo(
                        vd.getNameAsString(),
                        resolveAstTypeWithParams(vd.getType(), constructorTypeParams),
                        vdStart,
                        endLine,
                        false));
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
    private void indexMethods(
            String classFqn, List<String> typeParams, TypeDeclaration<?> typeDecl) {
        for (MethodDeclaration md : typeDecl.getMethods()) {
            String methodKey = makeMethodKey(classFqn, md.getNameAsString());
            List<VariableInfo> vars =
                    methodVariablesMap.computeIfAbsent(methodKey, k -> new ArrayList<>());

            List<String> methodTypeParams = new ArrayList<>(typeParams);
            md.getTypeParameters().forEach(tp -> methodTypeParams.add(tp.getNameAsString()));

            int startLine = md.getRange().map(r -> r.begin.line).orElse(0);
            int endLine = md.getRange().map(r -> r.end.line).orElse(Integer.MAX_VALUE);

            for (Parameter p : md.getParameters()) {
                vars.add(new VariableInfo(
                        p.getNameAsString(),
                        resolveAstTypeWithParams(p.getType(), methodTypeParams),
                        startLine,
                        endLine,
                        true));
            } // for

            for (VariableDeclarator vd : md.findAll(VariableDeclarator.class)) {
                int vdStart = vd.getRange().map(r -> r.begin.line).orElse(startLine);
                vars.add(new VariableInfo(
                        vd.getNameAsString(),
                        resolveAstTypeWithParams(vd.getType(), methodTypeParams),
                        vdStart,
                        endLine,
                        false));
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
                    baseType = cit.resolve().describe();
                    int angleIdx = baseType.indexOf('<');
                    if (angleIdx != -1) {
                        baseType = baseType.substring(0, angleIdx);
                    } // if
                } catch (Throwable ignored) {
                    // fallback to simple name with scope
                } // try
                List<String> argTypes = new ArrayList<>();
                for (Type arg : cit.getTypeArguments().get()) {
                    argTypes.add(resolveAstTypeWithParams(arg, typeParams));
                } // for
                return baseType + "<" + String.join(", ", argTypes) + ">";
            } // if
        } else {
            if (type instanceof ArrayType at) {
                return resolveAstTypeWithParams(at.getComponentType(), typeParams) + "[]";
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
        Map<Integer, String> lineAllocations =
                allocationSiteMap.computeIfAbsent(classFqn, k -> new HashMap<>());

        for (VariableDeclarator vd : scopeNode.findAll(VariableDeclarator.class)) {
            if (vd.getInitializer().isPresent()) {
                int line = vd.getRange().map(r -> r.begin.line).orElse(0);
                if (line > 0) {
                    String typeStr = resolveAstType(vd.getType());
                    lineAllocations.put(line, typeStr);
                } // if
            } // if
        } // for

        for (AssignExpr ae : scopeNode.findAll(AssignExpr.class)) {
            int line = ae.getRange().map(r -> r.begin.line).orElse(0);
            if (line > 0) {
                try {
                    String typeStr = ae.getTarget().calculateResolvedType().describe();
                    lineAllocations.put(line, typeStr);
                } catch (Throwable ignored) {
                    // skip resolution on error
                } // try
            } // if
        } // for

        for (ObjectCreationExpr oce : scopeNode.findAll(ObjectCreationExpr.class)) {
            int line = oce.getRange().map(r -> r.begin.line).orElse(0);
            if (line > 0) {
                String allocType = resolveObjectCreationType(oce);
                if (allocType != null
                        && (!lineAllocations.containsKey(line) || allocType.contains("<"))) {
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
        try {
            return type.resolve().describe();
        } catch (Throwable t) {
            return type.asString();
        } // try
    } // resolveAstType

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
     * Checks if two raw type names match, accounting for package qualification.
     *
     * @param raw1 First raw type name.
     * @param raw2 Second raw type name.
     * @return True if they match exactly or as suffixes.
     */
    private static boolean rawTypeMatches(String raw1, String raw2) {
        if (raw1 == null || raw2 == null) {
            return false;
        } // if
        if (raw1.equals(raw2)) {
            return true;
        } // if
        return raw1.endsWith("." + raw2) || raw2.endsWith("." + raw1);
    } // rawTypeMatches

    /**
     * Checks if a class is a standard single-parameter collection class.
     *
     * @param classFqn Class FQN.
     * @return True if single-parameter collection.
     */
    private static boolean isStandardSingleParamCollection(String classFqn) {
        if (classFqn == null) {
            return false;
        } // if
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
        if (classFqn == null) {
            return false;
        } // if
        return "java.util.HashMap".equals(classFqn) || "HashMap".equals(classFqn)
                || "java.util.LinkedHashMap".equals(classFqn) || "LinkedHashMap".equals(classFqn)
                || "java.util.TreeMap".equals(classFqn) || "TreeMap".equals(classFqn)
                || "java.util.Hashtable".equals(classFqn) || "Hashtable".equals(classFqn)
                || "java.util.concurrent.ConcurrentHashMap".equals(classFqn)
                || "ConcurrentHashMap".equals(classFqn);
    } // isStandardMap

    /**
     * Looks up {@link ClassGenericInfo} by FQN or simple name.
     *
     * @param className Class name or FQN.
     * @return Found info or null.
     */
    private ClassGenericInfo findClassGenericInfo(String className) {
        if (className == null) {
            return null;
        } // if
        ClassGenericInfo info = classInfoMap.get(className);
        if (info != null) {
            return info;
        } // if
        for (Map.Entry<String, ClassGenericInfo> entry : classInfoMap.entrySet()) {
            if (rawTypeMatches(entry.getKey(), className)) {
                return entry.getValue();
            } // if
        } // for
        return null;
    } // findClassGenericInfo

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

        List<String> normalizedTargetArgs = targetArgs.stream()
                .map(AstTypeResolver::normalizeWildcard)
                .toList();

        List<String> hierarchy = new ArrayList<>(info.implementedTypes());
        info.extendedType().ifPresent(hierarchy::add);

        for (String parentType : hierarchy) {
            String parentRaw = extractRawTypeName(parentType);
            if (rawTypeMatches(parentRaw, targetRaw)) {
                List<String> parentArgs = extractTypeArguments(parentType);
                if (parentArgs.size() == normalizedTargetArgs.size()) {
                    Map<String, String> bindings = new HashMap<>();
                    for (int i = 0; i < parentArgs.size(); i++) {
                        String pArg = parentArgs.get(i);
                        if (info.typeParameters().contains(pArg)) {
                            bindings.put(pArg, normalizedTargetArgs.get(i));
                        } // if
                    } // for
                    List<String> resolvedArgs = new ArrayList<>();
                    for (String tp : info.typeParameters()) {
                        resolvedArgs.add(bindings.getOrDefault(tp, "java.lang.Object"));
                    } // for
                    return concreteClassFqn + "<" + String.join(", ", resolvedArgs) + ">";
                } // if
            } // if
        } // for

        return concreteClassFqn;
    } // resolveSubclassType

    /**
     * Reconciles a concrete runtime class with a declared reference type to produce
     * a fully parameterized concrete type name.
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
        List<String> declaredArgs = extractTypeArguments(declaredType).stream()
                .map(AstTypeResolver::normalizeWildcard)
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

        if (isStandardSingleParamCollection(runtimeClassFqn) && !declaredArgs.isEmpty()) {
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
            List<String> explicitArgs = oce.getTypeArguments().get().stream()
                    .map(AstTypeResolver::resolveAstType)
                    .map(AstTypeResolver::normalizeWildcard)
                    .toList();
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
        return Optional.ofNullable(classInfoMap.get(classFqn));
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
    public Optional<String> resolveVariableType(
            String classFqn, String methodName, String variableName, int lineNumber) {
        String methodKey = makeMethodKey(classFqn, methodName);
        List<VariableInfo> vars = methodVariablesMap.get(methodKey);
        if (vars == null || vars.isEmpty()) {
            return Optional.empty();
        } // if

        for (int i = vars.size() - 1; i >= 0; i--) {
            VariableInfo vi = vars.get(i);
            if (vi.name().equals(variableName)
                    && lineNumber >= vi.startLine()
                    && lineNumber <= vi.endLine()) {
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
        ClassGenericInfo info = classInfoMap.get(classFqn);
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
        try {
            Type parsed = StaticJavaParser.parseType(typeWithParams);
            return substituteType(parsed, bindings).asString();
        } catch (Throwable t) {
            String result = typeWithParams;
            for (Map.Entry<String, String> entry : bindings.entrySet()) {
                result = result.replaceAll(
                        "\\b" + Pattern.quote(entry.getKey()) + "\\b",
                        Matcher.quoteReplacement(entry.getValue()));
            } // for
            return result;
        } // try
    } // substituteType

    /**
     * Recursively substitutes type parameters inside an AST {@link Type}.
     *
     * @param type The AST type.
     * @param bindings The map of type parameter names to concrete type arguments.
     * @return Substituted AST Type.
     */
    private static Type substituteType(Type type, Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty() || type == null) {
            return type != null ? type.clone() : null;
        } // if
        if (type instanceof ClassOrInterfaceType cit) {
            String name = cit.getNameAsString();
            if (bindings.containsKey(name)
                    && cit.getScope().isEmpty()
                    && cit.getTypeArguments().isEmpty()) {
                String replacement = bindings.get(name);
                try {
                    return StaticJavaParser.parseType(replacement);
                } catch (Throwable t) {
                    return new ClassOrInterfaceType(null, replacement);
                } // try
            } // if
            ClassOrInterfaceType cloned = cit.clone();
            if (cloned.getTypeArguments().isPresent()) {
                com.github.javaparser.ast.NodeList<Type> newArgs =
                        new com.github.javaparser.ast.NodeList<>();
                for (Type arg : cloned.getTypeArguments().get()) {
                    newArgs.add(substituteType(arg, bindings));
                } // for
                cloned.setTypeArguments(newArgs);
            } // if
            return cloned;
        } else {
            if (type instanceof ArrayType at) {
                Type newComponent = substituteType(at.getComponentType(), bindings);
                return new ArrayType(newComponent, at.getOrigin(), at.getAnnotations());
            } else {
                if (type instanceof WildcardType wt) {
                    WildcardType cloned = wt.clone();
                    if (cloned.getExtendedType().isPresent()) {
                        cloned.setExtendedType(
                                (ReferenceType) substituteType(
                                        cloned.getExtendedType().get(), bindings));
                    } // if
                    if (cloned.getSuperType().isPresent()) {
                        cloned.setSuperType(
                                (ReferenceType) substituteType(
                                        cloned.getSuperType().get(), bindings));
                    } // if
                    return cloned;
                } // if
            } // if
        } // if
        return type.clone();
    } // substituteType
} // AstTypeResolver
