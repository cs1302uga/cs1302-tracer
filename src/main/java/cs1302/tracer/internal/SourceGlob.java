package cs1302.tracer.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.apiguardian.api.API;

/**
 * Portable, case-sensitive glob matching without filesystem access or recursive backtracking.
 * Patterns use {@code /} separators, {@code *}, {@code ?}, character classes, and non-nested
 * brace alternatives. A whole-component {@code **} matches zero or more path components.
 * Matching operates on Unicode code points; ordinary wildcards never consume a separator.
 *
 * <p>Brace alternatives may include separators, but every alternative must be nonempty and
 * every group must contain a comma. Every possible path through a pattern must have nonempty
 * components other than {@code .} and {@code ..}. Backslash escapes a glob metacharacter:
 * {@code * ? [ ] { } , ! -} or backslash. Separators cannot be escaped. Character classes
 * support ranges and leading {@code !} negation; an initial or final hyphen is literal.
 *
 * <p>Compilation retains alternatives as graph branches rather than expanding their Cartesian
 * product. Matching advances an iterative set of automaton states, using memory bounded by the
 * compiled pattern, independent of the number of possible alternative combinations.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class SourceGlob {

    private static final int EMPTY = 0;
    private static final int DOT = 1;
    private static final int DOT_DOT = 2;
    private static final int ORDINARY = 3;
    private static final int RECURSIVE = 4;
    private static final CharacterSet ANY = new CharacterSet(true, List.of());

    private final List<State> states;
    private final int start;
    private final int end;

    /**
     * Retains a completed automaton whose construction state is no longer exposed.
     *
     * @param states Compiled states.
     * @param start Initial state.
     * @param end Accepting state.
     */
    private SourceGlob(List<State> states, int start, int end) {
        this.states = List.copyOf(states);
        this.start = start;
        this.end = end;
    } // SourceGlob

    /**
     * Parses and validates a portable root-relative glob before any filesystem traversal.
     *
     * @param glob Portable glob pattern.
     * @return An immutable matcher that can be reused across paths.
     * @throws NullPointerException If {@code glob} is null.
     * @throws IllegalArgumentException If the glob is malformed.
     */
    public static SourceGlob compile(String glob) {
        return new Parser(Objects.requireNonNull(glob, "glob")).compile();
    } // compile

    /**
     * Tests a normalized relative path, without consulting the host filesystem.
     *
     * @param normalizedRelativePath A path using forward-slash separators.
     * @return Whether the entire path matches; empty, absolute, or dot-component paths do not match.
     * @throws NullPointerException If the path is null.
     */
    public boolean matches(String normalizedRelativePath) {
        Objects.requireNonNull(normalizedRelativePath, "normalizedRelativePath");
        for (String component : normalizedRelativePath.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                return false;
            } // if
        } // for
        BitSet active = new BitSet(this.states.size());
        active.set(this.start);
        close(active);
        active = advance(active, '/');
        for (int offset = 0; offset < normalizedRelativePath.length();) {
            int codePoint = normalizedRelativePath.codePointAt(offset);
            active = advance(active, codePoint);
            offset += Character.charCount(codePoint);
        } // for
        return active.get(this.end);
    } // matches

    /**
     * Consumes one code point across all currently reachable states.
     *
     * @param active Current states including their epsilon closure.
     * @param codePoint Input code point.
     * @return The next states including their epsilon closure.
     */
    private BitSet advance(BitSet active, int codePoint) {
        BitSet next = new BitSet(this.states.size());
        for (int index = active.nextSetBit(0); index >= 0; index = active.nextSetBit(index + 1)) {
            for (CharacterEdge edge : this.states.get(index).edges) {
                if (edge.accepts(codePoint)) {
                    next.set(edge.target());
                } // if
            } // for
        } // for
        close(next);
        return next;
    } // advance

    /**
     * Adds states reachable without consuming input, visiting each state at most once.
     *
     * @param active States updated in place.
     */
    private void close(BitSet active) {
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        active.stream().forEach(pending::addLast);
        while (!pending.isEmpty()) {
            for (int target : this.states.get(pending.removeFirst()).epsilon) {
                if (!active.get(target)) {
                    active.set(target);
                    pending.addLast(target);
                } // if
            } // for
        } // while
    } // close

    /** A closed interval of Unicode code points. */
    private record Range(int first, int last) {} // Range

    /** A character class, which never consumes a separator even when negated. */
    private record CharacterSet(boolean negated, List<Range> ranges) {

        /**
         * Tests one non-separator code point against the class.
         *
         * @param codePoint Code point to test.
         * @return Whether it belongs to the class.
         */
        private boolean accepts(int codePoint) {
            if (codePoint == '/') {
                return false;
            } // if
            for (Range range : this.ranges) {
                if (codePoint >= range.first() && codePoint <= range.last()) {
                    return !this.negated;
                } // if
            } // for
            return this.negated;
        } // accepts

    } // CharacterSet

    /** A consuming transition, with null characters identifying an exact literal. */
    private record CharacterEdge(int literal, CharacterSet characters, int target) {

        /**
         * Tests the transition's literal or character class.
         *
         * @param codePoint Code point to test.
         * @return Whether the transition consumes this point.
         */
        private boolean accepts(int codePoint) {
            return this.characters == null ? this.literal == codePoint : this.characters.accepts(codePoint);
        } // accepts

    } // CharacterEdge

    /** One character-automaton state, populated only during compilation. */
    private static final class State {

        private final List<Integer> epsilon = new ArrayList<>();
        private final List<CharacterEdge> edges = new ArrayList<>();

    } // State

    /** Token categories before recursive path components are lowered to character transitions. */
    private enum Kind {

        LITERAL, STAR, ONE, CLASS, RECURSIVE

    } // Kind

    /** One parsed token transition; literal and class data depend on its kind. */
    private record Token(Kind kind, int literal, CharacterSet characters, int target) {} // Token

    /** One token-graph state, with alternatives represented by epsilon branches. */
    private static final class TokenState {

        private final List<Integer> epsilon = new ArrayList<>();
        private final List<Token> tokens = new ArrayList<>();

    } // TokenState

    /** A graph position paired with the classification of its current path component. */
    private record Position(int node, int component) {} // Position

    /** Parses syntax, validates component boundaries, and constructs a character automaton. */
    private static final class Parser {

        private final String glob;
        private final List<TokenState> graph = new ArrayList<>();
        private final List<State> states = new ArrayList<>();
        private int offset;

        /**
         * Starts parsing one immutable pattern.
         *
         * @param glob Pattern text.
         */
        private Parser(String glob) {
            this.glob = glob;
        } // Parser

        /**
         * Compiles the validated token graph with a synthetic leading separator.
         *
         * @return The compiled matcher.
         */
        private SourceGlob compile() {
            int first = tokenState();
            int end = sequence(first, false);
            validate(end);
            int start = tokenState();
            this.graph.get(start).tokens.add(new Token(Kind.LITERAL, '/', null, first));
            for (int index = 0; index < this.graph.size(); index++) {
                state();
            } // for
            for (int index = 0; index < this.graph.size(); index++) {
                this.states.get(index).epsilon.addAll(this.graph.get(index).epsilon);
                for (Token token : this.graph.get(index).tokens) {
                    lower(index, token);
                } // for
            } // for
            return new SourceGlob(this.states, start, end);
        } // compile

        /**
         * Parses a sequence until a brace separator or the pattern's end.
         *
         * @param current Initial graph state.
         * @param grouped Whether comma and closing brace terminate this sequence.
         * @return The final graph state.
         */
        private int sequence(int current, boolean grouped) {
            while (this.offset < this.glob.length()) {
                int symbol = this.glob.codePointAt(this.offset);
                if (symbol == ',' || symbol == '}') {
                    if (grouped) {
                        return current;
                    } // if
                    throw invalid("unexpected brace delimiter");
                } // if
                this.offset += Character.charCount(symbol);
                if (symbol == '{') {
                    if (grouped) {
                        throw invalid("nested brace alternatives are not supported");
                    } // if
                    current = alternatives(current);
                } else {
                    int next = tokenState();
                    this.graph.get(current).tokens.add(token(symbol, next));
                    current = next;
                } // if
            } // while
            return current;
        } // sequence

        /**
         * Parses nonempty alternatives without expanding subsequent combinations.
         *
         * @param start State before the opening brace.
         * @return State shared after the closing brace.
         */
        private int alternatives(int start) {
            int end = tokenState();
            int commas = 0;
            while (true) {
                int branch = tokenState();
                this.graph.get(start).epsilon.add(branch);
                int before = this.offset;
                int last = sequence(branch, true);
                if (before == this.offset) {
                    throw invalid("brace alternatives must be nonempty");
                } // if
                this.graph.get(last).epsilon.add(end);
                if (this.offset == this.glob.length()) {
                    throw invalid("unclosed brace alternatives");
                } // if
                if (this.glob.charAt(this.offset++) == '}') {
                    if (commas == 0) {
                        throw invalid("brace alternatives require a comma");
                    } // if
                    return end;
                } // if
                commas++;
            } // while
        } // alternatives

        /**
         * Parses one consuming token after its first symbol has been read.
         *
         * @param symbol First symbol.
         * @param target Destination state.
         * @return The token with any trailing syntax consumed.
         */
        private Token token(int symbol, int target) {
            return switch (symbol) {
                case '*' -> new Token(take('*') ? Kind.RECURSIVE : Kind.STAR, 0, null, target);
                case '?' -> new Token(Kind.ONE, 0, null, target);
                case '[' -> new Token(Kind.CLASS, 0, characterClass(), target);
                case ']' -> throw invalid("unexpected closing character class");
                case '\\' -> new Token(Kind.LITERAL, escaped(), null, target);
                default -> new Token(Kind.LITERAL, symbol, null, target);
            }; // switch
        } // token

        /**
         * Consumes one ASCII symbol if it is next in the pattern.
         *
         * @param symbol Expected symbol.
         * @return Whether it was consumed.
         */
        private boolean take(char symbol) {
            if (this.offset < this.glob.length() && this.glob.charAt(this.offset) == symbol) {
                this.offset++;
                return true;
            } // if
            return false;
        } // take

        /**
         * Reads a metacharacter escaped by the preceding backslash.
         *
         * @return Its literal code point.
         */
        private int escaped() {
            if (this.offset == this.glob.length()) {
                throw invalid("trailing escape");
            } // if
            int symbol = this.glob.codePointAt(this.offset);
            this.offset += Character.charCount(symbol);
            if ("*?[]{}!,\\-".indexOf(symbol) < 0) {
                throw invalid("backslash must escape a glob metacharacter");
            } // if
            return symbol;
        } // escaped

        /**
         * Parses inclusive code-point ranges and optional leading negation.
         *
         * @return The parsed class.
         */
        private CharacterSet characterClass() {
            boolean negated = take('!');
            List<Range> ranges = new ArrayList<>();
            while (this.offset < this.glob.length() && this.glob.charAt(this.offset) != ']') {
                boolean initialHyphen = ranges.isEmpty() && this.glob.charAt(this.offset) == '-';
                int first = classCharacter();
                int last = first;
                if (!initialHyphen && this.offset + 1 < this.glob.length() && this.glob.charAt(this.offset) == '-'
                        && this.glob.charAt(this.offset + 1) != ']') {
                    this.offset++;
                    last = classCharacter();
                    if (first > last) {
                        throw invalid("reversed character-class range");
                    } // if
                } // if
                ranges.add(new Range(first, last));
            } // while
            if (!take(']')) {
                throw invalid("unclosed character class");
            } // if
            if (ranges.isEmpty()) {
                throw invalid("character class must be nonempty");
            } // if
            return new CharacterSet(negated, List.copyOf(ranges));
        } // characterClass

        /**
         * Reads a literal class member or endpoint, accepting escaped metacharacters.
         *
         * @return The code point.
         */
        private int classCharacter() {
            int symbol = this.glob.codePointAt(this.offset);
            this.offset += Character.charCount(symbol);
            if (symbol == '\\') {
                return escaped();
            } // if
            if (symbol == '/' || symbol == '[') {
                throw invalid("character classes cannot contain separators or nested classes");
            } // if
            return symbol;
        } // classCharacter

        /**
         * Validates every possible component boundary using five finite component states.
         *
         * @param end Accepting token state.
         */
        private void validate(int end) {
            boolean[][] visited = new boolean[this.graph.size()][5];
            ArrayDeque<Position> pending = new ArrayDeque<>();
            pending.add(new Position(0, EMPTY));
            while (!pending.isEmpty()) {
                Position position = pending.removeFirst();
                if (visited[position.node()][position.component()]) {
                    continue;
                } // if
                visited[position.node()][position.component()] = true;
                if (position.node() == end) {
                    requireComponent(position.component());
                } // if
                TokenState node = this.graph.get(position.node());
                for (int target : node.epsilon) {
                    pending.add(new Position(target, position.component()));
                } // for
                for (Token token : node.tokens) {
                    pending.add(new Position(token.target(), component(position.component(), token)));
                } // for
            } // while
        } // validate

        /**
         * Advances component validation over a token without enumerating alternative strings.
         *
         * @param previous Current component classification.
         * @param token Next token.
         * @return Updated classification.
         */
        private int component(int previous, Token token) {
            if (token.kind() == Kind.LITERAL && token.literal() == '/') {
                requireComponent(previous);
                return EMPTY;
            } // if
            if (token.kind() == Kind.RECURSIVE) {
                if (previous != EMPTY) {
                    throw invalid("** must occupy an entire path component");
                } // if
                return RECURSIVE;
            } // if
            if (previous == RECURSIVE) {
                throw invalid("** must occupy an entire path component");
            } // if
            if (token.kind() == Kind.LITERAL && token.literal() == '.') {
                return switch (previous) {
                    case EMPTY -> DOT;
                    case DOT -> DOT_DOT;
                    default -> ORDINARY;
                }; // switch
            } // if
            return ORDINARY;
        } // component

        /**
         * Requires a nonempty component other than a current- or parent-directory marker.
         *
         * @param component Component classification.
         */
        private void requireComponent(int component) {
            if (component < ORDINARY) {
                throw invalid("paths must be relative with no empty, . or .. components");
            } // if
        } // requireComponent

        /**
         * Lowers one token into literal, class, or epsilon character transitions.
         *
         * @param source Source state.
         * @param token Token being lowered.
         */
        private void lower(int source, Token token) {
            State node = this.states.get(source);
            switch (token.kind()) {
            case LITERAL:
                node.edges.add(new CharacterEdge(token.literal(), null, token.target()));
                if (token.literal() == '/') {
                    recursiveAfter(source, token.target());
                } // if
                break;
            case STAR:
                node.epsilon.add(token.target());
                node.edges.add(new CharacterEdge(0, ANY, source));
                break;
            case ONE:
                node.edges.add(new CharacterEdge(0, ANY, token.target()));
                break;
            case CLASS:
                node.edges.add(new CharacterEdge(0, token.characters(), token.target()));
                break;
            case RECURSIVE:
                // Replaced together with its preceding separator by recursiveAfter.
                break;
            } // switch
        } // lower

        /**
         * Replaces each reachable separator-plus-recursive token with zero or more components.
         *
         * @param source State before the separator.
         * @param after State after the separator, possibly branching through alternatives.
         */
        private void recursiveAfter(int source, int after) {
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(after);
            while (!pending.isEmpty()) {
                int next = pending.removeFirst();
                TokenState node = this.graph.get(next);
                pending.addAll(node.epsilon);
                for (Token token : node.tokens) {
                    if (token.kind() == Kind.RECURSIVE) {
                        recursive(source, token.target());
                    } // if
                } // for
            } // while
        } // recursiveAfter

        /**
         * Appends a small automaton for repeated slash-prefixed nonempty components.
         *
         * @param source Source state.
         * @param target State after the recursive token.
         */
        private void recursive(int source, int target) {
            int repeat = state();
            int slash = state();
            int body = state();
            this.states.get(source).epsilon.add(repeat);
            this.states.get(repeat).epsilon.add(target);
            this.states.get(repeat).edges.add(new CharacterEdge('/', null, slash));
            this.states.get(slash).edges.add(new CharacterEdge(0, ANY, body));
            this.states.get(body).edges.add(new CharacterEdge(0, ANY, body));
            this.states.get(body).epsilon.add(repeat);
        } // recursive

        /**
         * Allocates one token-graph state.
         *
         * @return Its index.
         */
        private int tokenState() {
            this.graph.add(new TokenState());
            return this.graph.size() - 1;
        } // tokenState

        /**
         * Allocates one character-automaton state.
         *
         * @return Its index.
         */
        private int state() {
            this.states.add(new State());
            return this.states.size() - 1;
        } // state

        /**
         * Creates a diagnostic containing the original pattern and a precise syntax reason.
         *
         * @param reason Syntax failure.
         * @return The exception to throw.
         */
        private IllegalArgumentException invalid(String reason) {
            return new IllegalArgumentException("Invalid source glob '" + this.glob + "': " + reason);
        } // invalid

    } // Parser

} // SourceGlob
