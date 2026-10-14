package cs1302.tracer.trace;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Method;
import com.sun.jdi.StackFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs source invocation bindings without inspecting argument values.
 * Contexts are rebuilt from suspended frames; no bindings survive a snapshot or job.
 */
final class InvocationTypeResolver {
    private final Map<String, Optional<MethodDeclaration>> methods = new HashMap<>();

    /**
     * Index source methods.
     * @param units Source compilation units.
     */
    InvocationTypeResolver(List<CompilationUnit> units) {
        for (CompilationUnit unit : units) {
            if (unit != null) {
                for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                    try {
                        var resolved = method.resolve();
                        String key = resolved.declaringType().getQualifiedName() + "#"
                                + resolved.getName() + descriptor(resolved);
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
    Optional<MethodDeclaration> declaration(Method method) {
        String key = method.declaringType().name().replace('$', '.') + "#"
                + method.name() + method.signature();
        return methods.getOrDefault(key, Optional.empty());
    } // declaration

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
    private static String descriptor(ResolvedMethodDeclaration method) {
        StringBuilder result = new StringBuilder("(");
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            var parameter = method.getParam(i);
            result.append(parameter.getType().erasure().toDescriptor());
        } // for
        return result.append(')').append(method.getReturnType().erasure().toDescriptor())
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
        Map<String, String> fallback = receiverBindings(target, resolver, objectTypes);
        declaration(target.location().method()).ifPresent(method ->
                method.getTypeParameters().forEach(tp ->
                        fallback.put(tp.getNameAsString(), tp.getNameAsString())));
        var targetMethod = declaration(target.location().method());
        if (targetMethod.isEmpty() || targetMethod.get().getTypeParameters().isEmpty()) {
            return fallback;
        } // if
        try {
            List<StackFrame> frames = target.thread().frames();
            int targetIndex = frames.indexOf(target);
            Map<String, String> callerBindings = Map.of();
            for (int i = frames.size() - 1; i >= targetIndex && targetIndex >= 0; i--) {
                StackFrame frame = frames.get(i);
                Map<String, String> current = receiverBindings(frame, resolver, objectTypes);
                Optional<MethodDeclaration> method = declaration(frame.location().method());
                if (method.isPresent()) {
                    method.get().getTypeParameters().forEach(tp ->
                            current.put(tp.getNameAsString(), tp.getNameAsString()));
                    if (i + 1 < frames.size() && !method.get().getTypeParameters().isEmpty()) {
                        current.putAll(invocationBindings(frames.get(i + 1),
                                frame.location().method(), callerBindings));
                    } // if
                } // if
                callerBindings = current;
            } // for
            return targetIndex >= 0 ? callerBindings : fallback;
        } catch (IncompatibleThreadStateException | RuntimeException ignored) {
            return fallback;
        } // try
    } // bindings

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
     * @param callerBindings Caller lexical bindings.
     * @return Supported source-level substitutions, or an empty map.
     */
    Map<String, String> invocationBindings(StackFrame caller, Method callee,
            Map<String, String> callerBindings) {
        Optional<MethodDeclaration> enclosing = declaration(caller.location().method());
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
     * @param callerBindings Caller lexical substitutions.
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
                    result.put(declaration.getTypeParameters().get(i).getName(),
                            args.get(i).resolve().describe());
                } // for
            } else {
                // Restrict inference to one non-poly argument and T or T[] formals.
                // Multi-argument LUBs, target typing, varargs and captures need more evidence.
                if (declaration.getNumberOfParams() != 1 || call.getArguments().size() != 1
                        || declaration.getParam(0).isVariadic()) {
                    return Map.of();
                } // if
                var formal = declaration.getParam(0).getType();
                var argument = call.getArgument(0);
                while (formal.isArray()) {
                    formal = formal.asArrayType().getComponentType();
                } // while
                if (!formal.isTypeVariable() || !(argument.isNameExpr()
                        || argument.isFieldAccessExpr() || argument.isCastExpr()
                        || argument.isArrayAccessExpr())) {
                    return Map.of();
                } // if
                CompilationUnit unit = call.findCompilationUnit().orElseThrow();
                if (!unit.containsData(AstTypeResolver.TYPE_SOLVER)) {
                    return Map.of();
                } // if
                var usage = JavaParserFacade.get(unit.getData(AstTypeResolver.TYPE_SOLVER))
                        .solveMethodAsUsage(call);
                for (var parameter : declaration.getTypeParameters()) {
                    usage.typeParametersMap().getValueBySignature(parameter.getQualifiedName())
                            .ifPresent(type -> result.put(parameter.getName(), type.describe()));
                } // for
            } // if
            result.replaceAll((name, type) -> AstTypeResolver.substituteType(type, callerBindings));
            // Do not promote unbound caller variables or solver capture/intersection labels.
            result.entrySet().removeIf(entry -> !isConcrete(entry.getValue()));
            return result;
        } catch (RuntimeException ignored) {
            return Map.of();
        } // try
    } // infer

    /**
     * Check that a solver result has a representable, concrete reference type.
     * @param type Solver type description.
     * @return Whether the substitution can safely be displayed.
     */
    private static boolean isConcrete(String type) {
        String remainder = type.replaceAll(
                "[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+", "");
        return !remainder.equals(type) && remainder.matches("[<>,\\[\\] ]*");
    } // isConcrete
} // InvocationTypeResolver
