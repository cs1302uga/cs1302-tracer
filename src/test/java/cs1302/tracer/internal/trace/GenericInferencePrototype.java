package cs1302.tracer.internal.trace;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.HashMap;
import java.util.Map;

/**
 * THROWAWAY, test-only experiment: can structural equality safely extend inference? No compiler-oracle input, runtime
 * values, shared caches, or production integration. Intentionally restricted to one unbounded method variable and exact
 * type constructors.
 */
final class GenericInferencePrototype {
    private GenericInferencePrototype() {}

    static Map<String, String> infer(MethodCallExpr call) {
        try {
            var method = call.resolve();
            if (call.getTypeArguments().isPresent() || method.getTypeParameters().size() != 1
                    || !method.getTypeParameters().getFirst().getBounds().isEmpty() || method.getNumberOfParams() == 0
                    || method.getNumberOfParams() != call.getArguments().size()) {
                return Map.of();
            }
            var parameter = method.getTypeParameters().getFirst();
            Map<String, String> bindings = new HashMap<>();
            for (int i = 0; i < method.getNumberOfParams(); i++) {
                var expression = call.getArgument(i);
                if (!independent(expression)) {
                    return Map.of();
                }
                var formal = method.getParam(i).getType();
                var actual = expression.calculateResolvedType();
                // Fixed-arity varargs only: require an existing array, never expansion.
                if (method.getParam(i).isVariadic() && !actual.isArray()) {
                    return Map.of();
                }
                if (!match(formal, actual, parameter.getQualifiedName(), bindings, 0)) {
                    return Map.of();
                }
            }
            String value = bindings.get(parameter.getQualifiedName());
            return value == null ? Map.of() : Map.of(parameter.getName(), value);
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private static boolean independent(Expression expression) {
        if (expression.isNameExpr() || expression.isFieldAccessExpr() || expression.isCastExpr()
                || expression.isArrayAccessExpr()) {
            return true;
        }
        if (expression.isLiteralExpr()) {
            return !expression.isNullLiteralExpr();
        }
        if (expression.isObjectCreationExpr()) {
            var creation = expression.asObjectCreationExpr();
            return !creation.getType().isUsingDiamondOperator() && creation.getAnonymousClassBody().isEmpty()
                    && creation.getArguments().stream().allMatch(GenericInferencePrototype::independent);
        }
        if (expression.isMethodCallExpr()) {
            var nested = expression.asMethodCallExpr();
            return nested.resolve().getTypeParameters().isEmpty()
                    && nested.getArguments().stream().allMatch(GenericInferencePrototype::independent);
        }
        return false;
    }

    private static boolean match(ResolvedType formal, ResolvedType actual, String identity,
            Map<String, String> bindings, int depth) {
        if (depth > 32) {
            return false;
        }
        if (formal.isTypeVariable()) {
            if (!formal.asTypeVariable().asTypeParameter().getQualifiedName().equals(identity)
                    || !concrete(actual, false, depth + 1)) {
                return false;
            }
            String previous = bindings.putIfAbsent(identity, actual.describe());
            return previous == null || previous.equals(actual.describe());
        }
        if (formal.isArray()) {
            return actual.isArray() && match(formal.asArrayType().getComponentType(),
                    actual.asArrayType().getComponentType(), identity, bindings, depth + 1);
        }
        if (formal.isReferenceType() && actual.isReferenceType()) {
            var left = formal.asReferenceType();
            var right = actual.asReferenceType();
            if (!left.getQualifiedName().equals(right.getQualifiedName()) || left.isRawType() || right.isRawType()
                    || left.typeParametersValues().size() != right.typeParametersValues().size()) {
                return false;
            }
            for (int i = 0; i < left.typeParametersValues().size(); i++) {
                if (!match(left.typeParametersValues().get(i), right.typeParametersValues().get(i), identity, bindings,
                        depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return formal.isPrimitive() && actual.isPrimitive() && formal.equals(actual);
    }

    private static boolean concrete(ResolvedType type, boolean arrayComponent, int depth) {
        if (depth > 32) {
            return false;
        }
        if (type.isArray()) {
            return concrete(type.asArrayType().getComponentType(), true, depth + 1);
        }
        if (type.isPrimitive()) {
            return arrayComponent;
        }
        if (!type.isReferenceType() || type.asReferenceType().isRawType()
                || type.asReferenceType().getTypeDeclaration().isEmpty()) {
            return false;
        }
        return type.asReferenceType().typeParametersValues().stream()
                .allMatch(argument -> concrete(argument, false, depth + 1));
    }
}
