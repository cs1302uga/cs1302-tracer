package cs1302.tracer.trace;

import cs1302.tracer.execution.NestingException;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ArrayType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded iterative traversal before invoking recursive JavaParser type helpers. */
final class TypeTraversal {
    /** Allows legal 255-dimensional arrays with room for their component type. */
    static final int MAX_DEPTH = 384;
    /** Allows 64 generic wrappers plus a leaf type and its name node. */
    static final int MAX_NON_ARRAY_DEPTH = 66;
    /** Bounds nesting in type fragments before parsing, including replacement bindings. */
    static final int MAX_TEXT_DEPTH = 64;

    /** Prevents utility construction. */
    private TypeTraversal() {} // TypeTraversal

    /**
     * Collects types in child-before-parent order after checking every AST child.
     * @param root Type root.
     * @return Postorder types.
     */
    static List<Type> postorder(Type root) {
        var pending = new ArrayDeque<Frame>();
        var types = new ArrayList<Type>();
        pending.push(new Frame(root, 1, root instanceof ArrayType ? 0 : 1));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (frame.depth() > MAX_DEPTH || frame.nonArrayDepth() > MAX_NON_ARRAY_DEPTH) {
                throw new NestingException("type_nesting_limit");
            } // if
            if (frame.node() instanceof Type type) {
                types.add(type);
            } // if
            for (Node child : frame.node().getChildNodes()) {
                pending.push(new Frame(child, frame.depth() + 1,
                        frame.nonArrayDepth() + (child instanceof ArrayType ? 0 : 1)));
            } // for
        } // while
        return types.reversed();
    } // postorder

    /**
     * Conservatively bounds delimiter nesting in a type fragment, not a Java source file.
     * Annotation text also counts; syntax validation remains the parser's responsibility.
     * @param text Type fragment.
     */
    static void validateText(String text) {
        int depth = 0;
        int dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '(' || c == '[' || c == '{') {
                if (++depth > MAX_TEXT_DEPTH) {
                    throw new NestingException("type_nesting_limit");
                } // if
            } else {
                if (c == '>' || c == ')' || c == ']' || c == '}') {
                    depth = Math.max(0, depth - 1);
                } else {
                    if (c == '.' && ++dots > MAX_DEPTH) {
                        throw new NestingException("type_nesting_limit");
                    } // if
                } // if
            } // if
        } // for
    } // validateText

    /** Pending AST node and its path length. */
    private record Frame(Node node, int depth, int nonArrayDepth) {}
} // TypeTraversal
