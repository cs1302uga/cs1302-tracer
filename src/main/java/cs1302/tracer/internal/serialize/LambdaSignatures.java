package cs1302.tracer.internal.serialize;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.Type;
import cs1302.tracer.api.TypeStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Formats reconstructed lambda signature types without rewriting implementation bodies. */
final class LambdaSignatures {

    /** Prevents instantiation. */
    private LambdaSignatures() {} // LambdaSignatures

    /**
     * Applies the ordinary type presentation to return and parameter type tokens only.
     * A fresh parser keeps concurrent rendering independent. Non-method legacy payloads are preserved.
     *
     * @param implementation Reconstructed method implementation.
     * @param style Requested type qualification.
     * @return Implementation with only signature type text formatted.
     */
    static String format(String implementation, TypeStyle style) {
        if (style == TypeStyle.FQN) {
            return implementation;
        } // if
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT));
        ParseResult<MethodDeclaration> parsed = parser.parseMethodDeclaration(implementation);
        if (!parsed.isSuccessful()) {
            return implementation;
        } // if
        MethodDeclaration method = parsed.getResult().orElseThrow();
        List<Type> types = new ArrayList<>();
        types.add(method.getType());
        method.getParameters().forEach(parameter -> types.add(parameter.getType()));
        List<TokenRange> ranges = types.stream().map(type -> type.getTokenRange().orElseThrow())
                .sorted(Comparator.comparingInt(LambdaSignatures::offset).reversed()).toList();
        StringBuilder result = new StringBuilder(implementation);
        for (TokenRange range : ranges) {
            int start = offset(range);
            String original = range.toString();
            result.replace(start, start + original.length(), style.format(original));
        } // for
        return result.toString();
    } // format

    /**
     * Counts raw token text instead of parser columns, preserving tabs and line endings.
     * @param range Type tokens within the original implementation.
     * @return UTF-16 offset of the first type token.
     */
    private static int offset(TokenRange range) {
        int result = 0;
        JavaToken token = range.getBegin();
        while (token.getPreviousToken().isPresent()) {
            token = token.getPreviousToken().orElseThrow();
            result += token.getText().length();
        } // while
        return result;
    } // offset

} // LambdaSignatures
