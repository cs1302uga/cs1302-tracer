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
 */
public class AstTypeResolver {

    /**
     * Metadata for a class's generic type parameters, fields, and constructors.
     *
     * @param classFqn Fully qualified class name.
     * @param typeParameters List of generic type parameter names.
     * @param fieldTypes Map of field names to declared type strings.
     * @param fieldIsFinal Map of field names to final modifiers.
     */
    /**
     * Metadata for a class's generic type parameters, fields, and constructors.
     *
     * @param classFqn Fully qualified class name.
     * @param typeParameters List of generic type parameter names.
     * @param fieldTypes Map of field names to declared type strings.
     * @param fieldIsFinal Map of field names to final modifiers.
     */
    public record ClassGenericInfo(
            String classFqn,
            List<String> typeParameters,
            Map<String, String> fieldTypes,
            Map<String, Boolean> fieldIsFinal) {} // ClassGenericInfo

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
        if (typeDecl instanceof ClassOrInterfaceDeclaration cid) {
            cid.getTypeParameters().forEach(tp -> typeParams.add(tp.getNameAsString()));
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
                new ClassGenericInfo(classFqn, typeParams, fieldTypes, fieldIsFinal));

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
     * Safely resolve an {@link ObjectCreationExpr} target type.
     *
     * @param oce The object creation expression.
     * @return The resolved type string or raw type string.
     */
    public static String resolveObjectCreationType(ObjectCreationExpr oce) {
        if (oce == null) {
            return "java.lang.Object";
        } // if
        if (oce.getParentNode().isPresent()
                && oce.getParentNode().get() instanceof VariableDeclarator vd) {
            String varType = resolveAstType(vd.getType());
            if (varType.contains("<")) {
                return varType;
            } // if
        } // if
        if (oce.getParentNode().isPresent()
                && oce.getParentNode().get() instanceof AssignExpr ae) {
            try {
                String targetType = ae.getTarget().calculateResolvedType().describe();
                if (targetType.contains("<")) {
                    return targetType;
                } // if
            } catch (Throwable ignored) {
                // fall through
            } // try
        } // if
        try {
            String resolved = oce.calculateResolvedType().describe();
            if (resolved.contains("<")) {
                return resolved;
            } // if
        } catch (Throwable ignored) {
            // fall through
        } // try
        if (oce.getTypeArguments().isPresent() && !oce.getTypeArguments().get().isEmpty()) {
            try {
                return oce.getType().resolve().describe();
            } catch (Throwable ignored) {
                // fall through
            } // try
        } // if
        try {
            return oce.getType().resolve().describe();
        } catch (Throwable t) {
            return oce.getType().asString();
        } // try
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
