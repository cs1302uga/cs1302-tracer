package cs1302.tracer.trace;

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
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Method;
import com.sun.jdi.StackFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * Reconstructs source invocation bindings without inspecting argument values.
 * Contexts are rebuilt from suspended frames; no bindings survive a snapshot or job.
 */
final class InvocationTypeResolver {
    private final Map<String, Optional<CallableDeclaration<?>>> methods = new HashMap<>();

    /**
     * Index source methods.
     * @param units Source compilation units.
     */
    InvocationTypeResolver(List<CompilationUnit> units) {
        for (CompilationUnit unit : units) {
            if (unit != null) {
                for (var node : unit.findAll(CallableDeclaration.class)) {
                    CallableDeclaration<?> method = node;
                    try {
                        var resolved = resolve(method);
                        String key = resolved.declaringType().getQualifiedName() + "#"
                                + (method instanceof ConstructorDeclaration ? "<init>"
                                        : resolved.getName()) + descriptor(resolved);
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
     * @param method Active JVM method.
     * @return Unique source method, if available.
     */
    Optional<CallableDeclaration<?>> declaration(Method method) {
        String key = method.declaringType().name().replace('$', '.') + "#"
                + method.name() + method.signature();
        return methods.getOrDefault(key, Optional.empty());
    } // declaration

    /**
     * Resolve either kind of source callable.
     * @param declaration Source callable.
     * @return Resolved method or constructor.
     */
    private static ResolvedMethodLikeDeclaration resolve(CallableDeclaration<?> declaration) {
        return declaration instanceof MethodDeclaration method ? method.resolve()
                : ((ConstructorDeclaration) declaration).resolve();
    } // resolve

    /**
     * Compare source and JVM method identities.
     * @param source Resolved declaration.
     * @param target JVM method.
     * @return Whether identities match exactly.
     */
    private static boolean matches(ResolvedMethodDeclaration source, Method target) {
        return source.declaringType().getQualifiedName().equals(
                        target.declaringType().name().replace('$', '.'))
                && descriptor(source).equals(target.signature());
    } // matches

    /**
     * Include the implicit array dimension JavaParser omits from source varargs descriptors.
     * @param method Source method.
     * @return Erased JVM descriptor.
     */
    private static String descriptor(ResolvedMethodLikeDeclaration method) {
        StringBuilder result = new StringBuilder("(");
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            var parameter = method.getParam(i);
            result.append(parameter.getType().erasure().toDescriptor());
        } // for
        return result.append(')').append(method instanceof ResolvedMethodDeclaration declaration
                        ? declaration.getReturnType().erasure().toDescriptor() : "V")
                .toString();
    } // descriptor

    /**
     * Build the target frame's lexical bindings, from oldest caller to callee.
     * @param target Suspended target frame.
     * @param resolver Class type resolver.
     * @param objectTypes Existing receiver metadata.
     * @return Class bindings masked and supplemented by method bindings.
     */
    Map<String, String> bindings(StackFrame target, AstTypeResolver resolver,
            Map<Long, String> objectTypes) {
        try {
            return new FrameContexts(target.thread().frames(), resolver, objectTypes)
                    .bindings(target);
        } catch (IncompatibleThreadStateException | RuntimeException ignored) {
            return lexicalBindings(target, resolver, objectTypes);
        } // try
    } // bindings

    /**
     * Mask receiver parameters shadowed by the active callable.
     * @param frame Suspended frame.
     * @param resolver Class resolver.
     * @param objectTypes Receiver metadata.
     * @return Lexical fallback bindings.
     */
    private Map<String, String> lexicalBindings(StackFrame frame, AstTypeResolver resolver,
            Map<Long, String> objectTypes) {
        Map<String, String> result = receiverBindings(frame, resolver, objectTypes);
        declaration(frame.location().method()).ifPresent(method ->
                method.getTypeParameters().forEach(tp ->
                        result.put(tp.getNameAsString(), tp.getNameAsString())));
        return result;
    } // lexicalBindings

    /**
     * Binding cache owned by one suspended thread's extraction phase, never by the source index.
     */
    final class FrameContexts {
        private final List<StackFrame> frames;
        private final AstTypeResolver resolver;
        private final Map<Long, String> objectTypes;
        private final Map<Long, String> receiverTypes = new HashMap<>();
        private final Map<StackFrame, Map<String, String>> lexical = new HashMap<>();
        private final Map<StackFrame, Map<String, String>> qualified = new HashMap<>();
        private int next;

        /**
         * Create an extraction-local cache.
         * @param frames Suspended frames, newest first.
         * @param resolver Class resolver.
         * @param objectTypes Mutable receiver metadata.
         */
        FrameContexts(List<StackFrame> frames, AstTypeResolver resolver,
                Map<Long, String> objectTypes) {
            this.frames = frames;
            this.resolver = resolver;
            this.objectTypes = objectTypes;
            this.next = frames.size() - 1;
            for (StackFrame frame : frames) {
                if (frame.thisObject() != null) {
                    long id = frame.thisObject().uniqueID();
                    receiverTypes.put(id, objectTypes.get(id));
                } // if
            } // for
        } // FrameContexts

        /**
         * Invalidate dependent contexts after receiver metadata changes.
         * @param id Object whose metadata may have changed.
         */
        void receiverTypeChanged(long id) {
            if (receiverTypes.containsKey(id)
                    && !Objects.equals(receiverTypes.get(id), objectTypes.get(id))) {
                receiverTypes.put(id, objectTypes.get(id));
                lexical.clear();
                qualified.clear();
                next = frames.size() - 1;
            } // if
        } // receiverTypeChanged

        /**
         * Reuse contexts, computing callers before their callees.
         * @param target Suspended target frame.
         * @return Target lexical bindings.
         */
        Map<String, String> bindings(StackFrame target) {
            if (lexical.containsKey(target)) {
                return lexical.get(target);
            } // if
            int index = frames.indexOf(target);
            if (index < 0) {
                return lexicalBindings(target, resolver, objectTypes);
            } // if
            while (next >= index) {
                int i = next--;
                StackFrame frame = frames.get(i);
                Map<String, String> current = lexicalBindings(frame, resolver, objectTypes);
                Map<String, String> identities = new HashMap<>();
                try {
                    var source = declaration(frame.location().method());
                    if (source.isPresent()) {
                        var resolved = resolve(source.get());
                        var receiver = receiverBindings(frame, resolver, objectTypes);
                        resolved.declaringType().getTypeParameters().forEach(tp -> {
                            if (receiver.containsKey(tp.getName())) {
                                identities.put(tp.getQualifiedName(), receiver.get(tp.getName()));
                            } // if
                        });
                        if (i + 1 < frames.size() && !resolved.getTypeParameters().isEmpty()) {
                            current.putAll(invocationBindings(frames.get(i + 1),
                                    frame.location().method(), qualified.get(frames.get(i + 1))));
                        } // if
                        resolved.getTypeParameters().forEach(tp ->
                                identities.put(tp.getQualifiedName(), current.get(tp.getName())));
                    } // if
                } catch (RuntimeException ignored) {
                    // Incomplete source metadata retains lexical fallback types.
                } // try
                lexical.put(frame, current);
                qualified.put(frame, identities);
            } // while
            return lexical.get(target);
        } // bindings
    } // FrameContexts

    /**
     * Obtain class bindings separately from method bindings.
     * @param frame Suspended frame.
     * @param resolver Class resolver.
     * @param objectTypes Receiver metadata.
     * @return Mutable class binding map.
     */
    private static Map<String, String> receiverBindings(StackFrame frame,
            AstTypeResolver resolver, Map<Long, String> objectTypes) {
        Map<String, String> result = new HashMap<>();
        if (frame.thisObject() != null) {
            String type = objectTypes.get(frame.thisObject().uniqueID());
            if (type != null) {
                result.putAll(resolver.getTypeBindings(
                        frame.location().method().declaringType().name(), type));
            } // if
        } // if
        return result;
    } // receiverBindings

    /**
     * Resolve only a unique source call on the caller's suspended line.
     * @param caller Caller frame.
     * @param callee Active callee method.
     * @param callerBindings Caller bindings keyed by type-parameter declaring identity.
     * @return Supported source-level substitutions, or an empty map.
     */
    Map<String, String> invocationBindings(StackFrame caller, Method callee,
            Map<String, String> callerBindings) {
        Optional<CallableDeclaration<?>> enclosing = declaration(caller.location().method());
        if (enclosing.isEmpty()) {
            return Map.of();
        } // if
        List<MethodCallExpr> candidates = new ArrayList<>();
        int line = caller.location().lineNumber();
        for (MethodCallExpr call : enclosing.get().findAll(MethodCallExpr.class)) {
            if (call.getRange().map(r -> r.begin.line <= line && r.end.line >= line)
                    .orElse(false) && call.getNameAsString().equals(callee.name())) {
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
     * @param call Unique invocation.
     * @param callerBindings Caller substitutions keyed by type-parameter declaring identity.
     * @return Concrete substitutions; uncertain variables retain their original names.
     */
    private static Map<String, String> infer(MethodCallExpr call,
            Map<String, String> callerBindings) {
        try {
            var declaration = call.resolve();
            Map<String, String> result = new HashMap<>();
            if (call.getTypeArguments().isPresent()) {
                var args = call.getTypeArguments().get();
                if (args.size() != declaration.getTypeParameters().size()) {
                    return Map.of();
                } // if
                for (int i = 0; i < args.size(); i++) {
                    var type = concreteType(args.get(i).resolve(), callerBindings, call, false);
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
                var formal = declaration.getParam(0).getType();
                var argument = call.getArgument(0);
                if (!(argument.isNameExpr() || argument.isFieldAccessExpr()
                        || argument.isCastExpr() || argument.isArrayAccessExpr())) {
                    return simpleExpressionBinding(call, declaration);
                } // if
                while (formal.isArray()) {
                    formal = formal.asArrayType().getComponentType();
                } // while
                if (!formal.isTypeVariable()) {
                    return Map.of();
                } // if
                var usage = JavaParserFacade.get(unit.getData(AstTypeResolver.TYPE_SOLVER))
                        .solveMethodAsUsage(call);
                for (var parameter : declaration.getTypeParameters()) {
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
     * @param call Unambiguous source invocation.
     * @param method Resolved callee with one non-variadic argument.
     * @return Concrete binding, or no binding outside the supported subset.
     */
    private static Map<String, String> simpleExpressionBinding(MethodCallExpr call,
            ResolvedMethodDeclaration method) {
        // JavaParser also wraps expression-valued switch arms in ExpressionStmt.
        if (method.getTypeParameters().size() != 1
                || call.getParentNode().filter(parent -> parent instanceof ExpressionStmt
                        && !(parent.getParentNode().orElse(null) instanceof SwitchEntry))
                        .isEmpty()) {
            return Map.of();
        } // if
        if (!method.isStatic() && !call.getScope().map(scope ->
                independentReceiver(scope.calculateResolvedType())).orElse(false)) {
            return Map.of();
        } // if
        var parameter = method.getTypeParameters().getFirst();
        var formal = method.getParam(0).getType();
        var argument = call.getArgument(0);
        if (!parameter.getBounds().isEmpty() || !formal.isTypeVariable()
                || !formal.asTypeVariable().asTypeParameter().getQualifiedName()
                        .equals(parameter.getQualifiedName())
                || !independentExpression(argument, 0)) {
            return Map.of();
        } // if
        var type = argument.calculateResolvedType();
        if (!independentType(type, false, 0)) {
            return Map.of();
        } // if
        return concreteType(type, Map.of(), call, false)
                .map(name -> Map.of(parameter.getName(), name)).orElseGet(Map::of);
    } // simpleExpressionBinding

    /**
     * Check expression structure without evaluating guest code or inferring poly expressions.
     * @param expression Argument, nested argument, or receiver expression.
     * @param depth Current nesting depth.
     * @return Whether source types are independent of the outer invocation target.
     */
    private static boolean independentExpression(Expression expression, int depth) {
        if (depth > 32) {
            return false;
        } // if
        if (expression.isNameExpr() || expression.isFieldAccessExpr()
                || expression.isCastExpr() || expression.isArrayAccessExpr()
                || expression.isThisExpr() || expression.isSuperExpr()) {
            return true;
        } // if
        if (expression.isLiteralExpr()) {
            return !expression.isNullLiteralExpr();
        } // if
        if (expression.isObjectCreationExpr()) {
            var creation = expression.asObjectCreationExpr();
            return creation.getScope().isEmpty() && creation.getAnonymousClassBody().isEmpty()
                    && !creation.getType().isUsingDiamondOperator()
                    && creation.getArguments().stream()
                            .allMatch(arg -> independentExpression(arg, depth + 1));
        } // if
        if (expression.isMethodCallExpr()) {
            var nested = expression.asMethodCallExpr();
            // The solver erases array clone's covariant result to Object.
            if (nested.getNameAsString().equals("clone") && nested.getScope().isPresent()
                    && nested.getScope().get().calculateResolvedType().isArray()) {
                return false;
            } // if
            var method = nested.resolve();
            return method.getTypeParameters().isEmpty()
                    && independentType(method.getReturnType(), false, 0)
                    && (method.isStatic() || nested.getScope().map(scope ->
                            independentReceiver(scope.calculateResolvedType())).orElse(false))
                    && nested.getScope().map(scope -> independentExpression(scope, depth + 1))
                            .orElse(true)
                    && nested.getArguments().stream()
                            .allMatch(arg -> independentExpression(arg, depth + 1));
        } // if
        return false;
    } // independentExpression

    /**
     * Reject receivers whose raw ancestry can erase even fixed parameterized return types.
     * @param type Explicit receiver type.
     * @return Whether receiver erasure cannot change the declared result.
     */
    private static boolean independentReceiver(ResolvedType type) {
        // The solver may represent a raw superclass as Box<U>, not as a raw type.
        // Require concrete ancestor arguments as well as the explicit receiver's type.
        return independentType(type, false, 0) && (!type.isReferenceType()
                || type.asReferenceType().getAllAncestors().stream()
                        .allMatch(ancestor -> independentType(ancestor, false, 0)));
    } // independentReceiver

    /**
     * Reject raw, captured, or receiver-dependent types in the new expression subset.
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
        var declaration = type.asReferenceType().getTypeDeclaration().orElseThrow();
        if (declaration.toAst().filter(node -> node instanceof TypeDeclaration<?> named
                && named.getFullyQualifiedName().isEmpty()).isPresent()) {
            return false;
        } // if
        // Enclosing generic owners need a representation and substitution policy of their own.
        var owner = declaration.containerType();
        int owners = 0;
        while (owner.isPresent()) {
            if (++owners > 32 || !owner.get().getTypeParameters().isEmpty()) {
                return false;
            } // if
            owner = owner.get().containerType();
        } // while
        return !declaration.isAnonymousClass() && type.asReferenceType().typeParametersValues()
                .stream().allMatch(arg -> independentType(arg, false, depth + 1));
    } // independentType

    /**
     * Check that a solver result has a representable, concrete reference type.
     * @param type Solver type, retaining type-parameter declaring identity.
     * @param bindings Qualified caller substitutions.
     * @param call Source context for resolving receiver metadata.
     * @param arrayComponent Whether a primitive component is permitted.
     * @return A concrete display type, if supported.
     */
    private static Optional<String> concreteType(ResolvedType type, Map<String, String> bindings,
            MethodCallExpr call, boolean arrayComponent) {
        if (type.isArray()) {
            return concreteType(type.asArrayType().getComponentType(), bindings, call, true)
                    .map(component -> component + "[]");
        } // if
        if (type.isPrimitive()) {
            return arrayComponent ? Optional.of(type.describe()) : Optional.empty();
        } // if
        if (type.isTypeVariable()) {
            String binding = bindings.get(
                    type.asTypeVariable().asTypeParameter().getQualifiedName());
            if (binding == null || binding.isBlank()) {
                return Optional.empty();
            } // if
            var unit = call.findCompilationUnit().orElseThrow();
            var resolved = JavaParserFacade.get(unit.getData(AstTypeResolver.TYPE_SOLVER))
                    .convert(com.github.javaparser.StaticJavaParser.parseType(binding), call);
            return concreteType(resolved, Map.of(), call, arrayComponent);
        } // if
        if (!type.isReferenceType()) {
            return Optional.empty();
        } // if
        var reference = type.asReferenceType();
        List<String> arguments = new ArrayList<>();
        for (ResolvedType argument : reference.typeParametersValues()) {
            var concrete = concreteType(argument, bindings, call, false);
            if (concrete.isEmpty()) {
                return Optional.empty();
            } // if
            arguments.add(concrete.get());
        } // for
        return Optional.of(reference.getQualifiedName()
                + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">"));
    } // concreteType
} // InvocationTypeResolver
