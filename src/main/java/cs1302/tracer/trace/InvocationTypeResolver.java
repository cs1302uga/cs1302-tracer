package cs1302.tracer.trace;

import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.sun.jdi.Method;
import com.sun.jdi.StackFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs source invocation bindings without inspecting argument values. Contexts are rebuilt from suspended
 * frames; no bindings survive a snapshot or job.
 */
final class InvocationTypeResolver {
    private final Map<String, Optional<CallableDeclaration<?>>> methods = new HashMap<>();

    /**
     * Index source methods.
     *
     * @param units Source compilation units.
     */
    InvocationTypeResolver(List<CompilationUnit> units) {
        for (CompilationUnit unit : units) {
            if (unit != null) {
                for (CallableDeclaration<?> method : unit.findAll(CallableDeclaration.class)) {
                    try {
                        ResolvedMethodLikeDeclaration resolved = resolve(method);
                        String key = resolved.declaringType().getQualifiedName() + "#"
                                + (method instanceof ConstructorDeclaration ? "<init>" : resolved.getName())
                                + descriptor(resolved);
                        methods.merge(key, Optional.of(method), (left, right) -> Optional.empty());
                    } catch (RuntimeException ignored) {
                        // Missing dependencies preserve the existing unresolved metadata.
                    } // try
                } // for
            } // if
        } // for
    } // InvocationTypeResolver

    /**
     * Find an exact source declaration, including its erased JVM descriptor.
     *
     * @param method Active JVM method.
     * @return Unique source method, if available.
     */
    Optional<CallableDeclaration<?>> declaration(Method method) {
        String key = method.declaringType().name().replace('$', '.') + "#" + method.name() + method.signature();
        return methods.getOrDefault(key, Optional.empty());
    } // declaration

    /**
     * Resolve either kind of source callable.
     *
     * @param declaration Source callable.
     * @return Resolved method or constructor.
     */
    static ResolvedMethodLikeDeclaration resolve(CallableDeclaration<?> declaration) {
        return declaration instanceof MethodDeclaration method ? method.resolve()
                : ((ConstructorDeclaration) declaration).resolve();
    } // resolve

    /**
     * Compare source and JVM method identities.
     *
     * @param source Resolved declaration.
     * @param target JVM method.
     * @return Whether identities match exactly.
     */
    private static boolean matches(ResolvedMethodDeclaration source, Method target) {
        return source.declaringType().getQualifiedName().equals(target.declaringType().name().replace('$', '.'))
                && descriptor(source).equals(target.signature());
    } // matches

    /**
     * Include the implicit array dimension JavaParser omits from source varargs descriptors.
     *
     * @param method Source method.
     * @return Erased JVM descriptor.
     */
    private static String descriptor(ResolvedMethodLikeDeclaration method) {
        StringBuilder result = new StringBuilder("(");
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration parameter = method.getParam(i);
            result.append(parameter.getType().erasure().toDescriptor());
        } // for
        return result.append(')')
                .append(method instanceof ResolvedMethodDeclaration declaration
                        ? declaration.getReturnType().erasure().toDescriptor()
                        : "V")
                .toString();
    } // descriptor

    /**
     * Mask receiver parameters shadowed by the active callable.
     *
     * @param frame Suspended frame.
     * @param resolver Class resolver.
     * @param objectTypes Receiver metadata.
     * @return Lexical fallback bindings.
     */
    Map<String, String> lexicalBindings(StackFrame frame, AstTypeResolver resolver, Map<Long, String> objectTypes) {
        Map<String, String> result = receiverBindings(frame, resolver, objectTypes);
        declaration(frame.location().method()).ifPresent(method -> method.getTypeParameters()
                .forEach(tp -> result.put(tp.getNameAsString(), tp.getNameAsString())));
        return result;
    } // lexicalBindings

    /**
     * Obtain class bindings separately from method bindings.
     *
     * @param frame Suspended frame.
     * @param resolver Class resolver.
     * @param objectTypes Receiver metadata.
     * @return Mutable class binding map.
     */
    static Map<String, String> receiverBindings(StackFrame frame, AstTypeResolver resolver,
            Map<Long, String> objectTypes) {
        Map<String, String> result = new HashMap<>();
        if (frame.thisObject() != null) {
            String type = objectTypes.get(frame.thisObject().uniqueID());
            if (type != null) {
                result.putAll(resolver.getTypeBindings(frame.location().method().declaringType().name(), type));
            } // if
        } // if
        return result;
    } // receiverBindings

    /**
     * Resolve only a unique source call on the caller's suspended line.
     *
     * @param caller Caller frame.
     * @param callee Active callee method.
     * @param callerBindings Caller bindings keyed by type-parameter declaring identity.
     * @return Supported source-level substitutions, or an empty map.
     */
    Map<String, String> invocationBindings(StackFrame caller, Method callee, Map<String, String> callerBindings) {
        Optional<CallableDeclaration<?>> enclosing = declaration(caller.location().method());
        if (enclosing.isEmpty()) {
            return Map.of();
        } // if
        List<MethodCallExpr> candidates = new ArrayList<>();
        int line = caller.location().lineNumber();
        for (MethodCallExpr call : enclosing.get().findAll(MethodCallExpr.class)) {
            if (call.getRange().map(r -> r.begin.line <= line && r.end.line >= line).orElse(false)
                    && call.getNameAsString().equals(callee.name())) {
                try {
                    if (matches(call.resolve(), callee)) {
                        candidates.add(call);
                    } // if
                } catch (RuntimeException ignored) {
                    // An unresolved candidate makes attribution ambiguous.
                    return Map.of();
                } // try
            } // if
        } // for
        if (candidates.size() != 1) {
            return Map.of();
        } // if
        return infer(candidates.getFirst(), callerBindings);
    } // invocationBindings

    /**
     * Ask the source solver for substitutions within the supported inference subset.
     *
     * @param call Unique invocation.
     * @param callerBindings Caller substitutions keyed by type-parameter declaring identity.
     * @return Concrete substitutions; uncertain variables retain their original names.
     */
    private static Map<String, String> infer(MethodCallExpr call, Map<String, String> callerBindings) {
        try {
            ResolvedMethodDeclaration declaration = call.resolve();
            Map<String, String> result = new HashMap<>();
            if (call.getTypeArguments().isPresent()) {
                NodeList<Type> args = call.getTypeArguments().get();
                if (args.size() != declaration.getTypeParameters().size()) {
                    return Map.of();
                } // if
                for (int i = 0; i < args.size(); i++) {
                    Optional<String> type = concreteType(args.get(i).resolve(), callerBindings, call, false);
                    if (type.isPresent()) {
                        result.put(declaration.getTypeParameters().get(i).getName(), type.get());
                    } // if
                } // for
            } else {
                // Restrict inference to one non-poly argument and T or T[] formals.
                // Multi-argument LUBs, target typing, varargs and captures need more evidence.
                if (declaration.getNumberOfParams() != 1 || call.getArguments().size() != 1
                        || declaration.getParam(0).isVariadic()) {
                    return Map.of();
                } // if
                CompilationUnit unit = call.findCompilationUnit().orElseThrow();
                if (!unit.containsData(AstTypeResolver.TYPE_SOLVER)) {
                    return Map.of();
                } // if
                ResolvedType formal = declaration.getParam(0).getType();
                Expression argument = call.getArgument(0);
                if (!(argument.isNameExpr() || argument.isFieldAccessExpr() || argument.isCastExpr()
                        || argument.isArrayAccessExpr())) {
                    return simpleExpressionBinding(call, declaration);
                } // if
                while (formal.isArray()) {
                    formal = formal.asArrayType().getComponentType();
                } // while
                if (!formal.isTypeVariable()) {
                    return Map.of();
                } // if
                MethodUsage usage = JavaParserFacade.get(unit.getData(AstTypeResolver.TYPE_SOLVER))
                        .solveMethodAsUsage(call);
                for (ResolvedTypeParameterDeclaration parameter : declaration.getTypeParameters()) {
                    usage.typeParametersMap().getValueBySignature(parameter.getQualifiedName())
                            .flatMap(type -> concreteType(type, callerBindings, call, false))
                            .ifPresent(type -> result.put(parameter.getName(), type));
                } // for
            } // if
            return result;
        } catch (RuntimeException ignored) {
            return Map.of();
        } // try
    } // infer

    /**
     * Infer an exact argument type for a single unbounded T formal.
     *
     * @param call Unambiguous source invocation.
     * @param method Resolved callee with one non-variadic argument.
     * @return Concrete binding, or no binding outside the supported subset.
     */
    private static Map<String, String> simpleExpressionBinding(MethodCallExpr call, ResolvedMethodDeclaration method) {
        // JavaParser also wraps expression-valued switch arms in ExpressionStmt.
        if (method.getTypeParameters().size() != 1
                || call.getParentNode().filter(parent -> parent instanceof ExpressionStmt
                        && !(parent.getParentNode().orElse(null) instanceof SwitchEntry)).isEmpty()) {
            return Map.of();
        } // if
        if (!method.isStatic()
                && !call.getScope().map(scope -> independentReceiver(scope.calculateResolvedType())).orElse(false)) {
            return Map.of();
        } // if
        ResolvedTypeParameterDeclaration parameter = method.getTypeParameters().getFirst();
        ResolvedType formal = method.getParam(0).getType();
        Expression argument = call.getArgument(0);
        if (!parameter.getBounds().isEmpty() || !formal.isTypeVariable()
                || !formal.asTypeVariable().asTypeParameter().getQualifiedName().equals(parameter.getQualifiedName())
                || !independentExpression(argument, 0)) {
            return Map.of();
        } // if
        ResolvedType type = argument.calculateResolvedType();
        if (!independentType(type, false, 0)) {
            return Map.of();
        } // if
        return concreteType(type, Map.of(), call, false).map(name -> Map.of(parameter.getName(), name))
                .orElseGet(Map::of);
    } // simpleExpressionBinding

    /**
     * Check expression structure without evaluating guest code or inferring poly expressions.
     *
     * @param expression Argument, nested argument, or receiver expression.
     * @param depth Current nesting depth.
     * @return Whether source types are independent of the outer invocation target.
     */
    private static boolean independentExpression(Expression expression, int depth) {
        if (depth > 32) {
            return false;
        } // if
        if (expression.isNameExpr() || expression.isFieldAccessExpr() || expression.isCastExpr()
                || expression.isArrayAccessExpr() || expression.isThisExpr() || expression.isSuperExpr()) {
            return true;
        } // if
        if (expression.isLiteralExpr()) {
            return !expression.isNullLiteralExpr();
        } // if
        if (expression.isObjectCreationExpr()) {
            ObjectCreationExpr creation = expression.asObjectCreationExpr();
            return creation.getScope().isEmpty() && creation.getAnonymousClassBody().isEmpty()
                    && !creation.getType().isUsingDiamondOperator()
                    && creation.getArguments().stream().allMatch(arg -> independentExpression(arg, depth + 1));
        } // if
        if (expression.isMethodCallExpr()) {
            MethodCallExpr nested = expression.asMethodCallExpr();
            // The solver erases array clone's covariant result to Object.
            if (nested.getNameAsString().equals("clone") && nested.getScope().isPresent()
                    && nested.getScope().get().calculateResolvedType().isArray()) {
                return false;
            } // if
            ResolvedMethodDeclaration method = nested.resolve();
            return method.getTypeParameters().isEmpty() && independentType(method.getReturnType(), false, 0)
                    && (method.isStatic() || nested.getScope()
                            .map(scope -> independentReceiver(scope.calculateResolvedType())).orElse(false))
                    && nested.getScope().map(scope -> independentExpression(scope, depth + 1)).orElse(true)
                    && nested.getArguments().stream().allMatch(arg -> independentExpression(arg, depth + 1));
        } // if
        return false;
    } // independentExpression

    /**
     * Reject receivers whose raw ancestry can erase even fixed parameterized return types.
     *
     * @param type Explicit receiver type.
     * @return Whether receiver erasure cannot change the declared result.
     */
    private static boolean independentReceiver(ResolvedType type) {
        // The solver may represent a raw superclass as Box<U>, not as a raw type.
        // Require concrete ancestor arguments as well as the explicit receiver's type.
        return independentType(type, false, 0) && (!type.isReferenceType() || type.asReferenceType().getAllAncestors()
                .stream().allMatch(ancestor -> independentType(ancestor, false, 0)));
    } // independentReceiver

    /**
     * Reject raw, captured, or receiver-dependent types in the new expression subset.
     *
     * @param type Declared expression type.
     * @param arrayComponent Whether a primitive is permitted as an array component.
     * @param depth Current nesting depth.
     * @return Whether the type can be represented without further substitutions.
     */
    private static boolean independentType(ResolvedType type, boolean arrayComponent, int depth) {
        if (depth > 32) {
            return false;
        } // if
        if (type.isArray()) {
            return independentType(type.asArrayType().getComponentType(), true, depth + 1);
        } // if
        if (type.isPrimitive()) {
            return arrayComponent;
        } // if
        if (!type.isReferenceType() || type.asReferenceType().isRawType()) {
            return false;
        } // if
        ResolvedReferenceTypeDeclaration declaration = type.asReferenceType().getTypeDeclaration().orElseThrow();
        if (declaration.toAst()
                .filter(node -> node instanceof TypeDeclaration<?> named && named.getFullyQualifiedName().isEmpty())
                .isPresent()) {
            return false;
        } // if
        // Enclosing generic owners need a representation and substitution policy of their own.
        Optional<ResolvedReferenceTypeDeclaration> owner = declaration.containerType();
        int owners = 0;
        while (owner.isPresent()) {
            if (++owners > 32 || !owner.get().getTypeParameters().isEmpty()) {
                return false;
            } // if
            owner = owner.get().containerType();
        } // while
        return !declaration.isAnonymousClass() && type.asReferenceType().typeParametersValues().stream()
                .allMatch(arg -> independentType(arg, false, depth + 1));
    } // independentType

    /**
     * Check that a solver result has a representable, concrete reference type.
     *
     * @param type Solver type, retaining type-parameter declaring identity.
     * @param bindings Qualified caller substitutions.
     * @param call Source context for resolving receiver metadata.
     * @param arrayComponent Whether a primitive component is permitted.
     * @return A concrete display type, if supported.
     */
    private static Optional<String> concreteType(ResolvedType type, Map<String, String> bindings, MethodCallExpr call,
            boolean arrayComponent) {
        return concreteType(type, bindings, call, arrayComponent, 0);
    } // concreteType

    /**
     * Resolves a concrete type within the shared representation depth ceiling.
     *
     * @param type Solver type at this position.
     * @param bindings Qualified caller substitutions.
     * @param call Source context for resolving receiver metadata.
     * @param arrayComponent Whether a primitive component is permitted.
     * @param depth Edges already traversed, including substitutions.
     * @return Concrete display type, or empty when unsupported or too deeply nested.
     */
    private static Optional<String> concreteType(ResolvedType type, Map<String, String> bindings, MethodCallExpr call,
            boolean arrayComponent, int depth) {
        if (depth >= TypeTraversal.MAX_DEPTH) {
            return Optional.empty();
        } // if
        if (type.isArray()) {
            return concreteType(type.asArrayType().getComponentType(), bindings, call, true, depth + 1)
                    .map(component -> component + "[]");
        } // if
        if (type.isPrimitive()) {
            return arrayComponent ? Optional.of(type.describe()) : Optional.empty();
        } // if
        if (type.isTypeVariable()) {
            String binding = bindings.get(type.asTypeVariable().asTypeParameter().getQualifiedName());
            if (binding == null || binding.isBlank()) {
                return Optional.empty();
            } // if
            TypeTraversal.validateText(binding);
            Type parsed = com.github.javaparser.StaticJavaParser.parseType(binding);
            TypeTraversal.postorder(parsed);
            CompilationUnit unit = call.findCompilationUnit().orElseThrow();
            ResolvedType resolved = JavaParserFacade.get(unit.getData(AstTypeResolver.TYPE_SOLVER)).convert(parsed,
                    call);
            return concreteType(resolved, Map.of(), call, arrayComponent, depth + 1);
        } // if
        if (!type.isReferenceType()) {
            return Optional.empty();
        } // if
        ResolvedReferenceType reference = type.asReferenceType();
        List<String> arguments = new ArrayList<>();
        for (ResolvedType argument : reference.typeParametersValues()) {
            Optional<String> concrete = concreteType(argument, bindings, call, false, depth + 1);
            if (concrete.isEmpty()) {
                return Optional.empty();
            } // if
            arguments.add(concrete.get());
        } // for
        return Optional.of(
                reference.getQualifiedName() + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">"));
    } // concreteType
} // InvocationTypeResolver
