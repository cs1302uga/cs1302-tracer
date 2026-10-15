package cs1302.tracer.trace;

import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import java.util.Optional;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.StackFrame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns reified type evidence and dependent frame bindings for one snapshot. Share this instance across captured threads
 * and heap traversal, never across snapshots. Traversal order remains the caller's responsibility; metadata access is
 * single-threaded.
 */
public final class ReifiedTypeMetadata {
    private final AstTypeResolver resolver;
    private final Map<Long, String> objectTypes = new HashMap<>();
    private final List<FrameBindings> contexts = new ArrayList<>();

    /**
     * Creates empty snapshot metadata.
     *
     * @param resolver Reusable source index, or null when source metadata is unavailable.
     */
    public ReifiedTypeMetadata(AstTypeResolver resolver) {
        this.resolver = resolver;
    } // ReifiedTypeMetadata

    /**
     * Provides the shared source index to extraction internals.
     *
     * @return Source index, or null when unavailable.
     */
    AstTypeResolver sourceTypes() {
        return resolver;
    } // sourceTypes

    /**
     * Reads the best retained type evidence.
     *
     * @param id Guest object identity.
     * @return Reified type name, or null if no evidence has been retained.
     */
    public String typeOf(long id) {
        return objectTypes.get(id);
    } // typeOf

    /**
     * Records fallback evidence without replacing existing evidence.
     *
     * @param id Guest object identity.
     * @param type Fallback type name, or null for unavailable evidence.
     */
    public void recordFallback(long id, String type) {
        if (type != null && !objectTypes.containsKey(id)) {
            update(id, type);
        } // if
    } // recordFallback

    /**
     * Retains a more specific candidate, preserving the first conflicting concrete type.
     *
     * @param id Guest object identity.
     * @param candidate Candidate already reconciled with the runtime type.
     * @param runtimeType Concrete runtime type name.
     */
    public void refine(long id, String candidate, String runtimeType) {
        if (isMoreSpecific(candidate, objectTypes.get(id), runtimeType)) {
            update(id, candidate);
        } // if
    } // refine

    /**
     * Reconciles declared evidence with the runtime type before refinement.
     *
     * @param id Guest object identity.
     * @param declaredType Declared type evidence, or null when unavailable.
     * @param runtimeType Concrete runtime type name.
     */
    public void refineDeclared(long id, String declaredType, String runtimeType) {
        if (declaredType != null) {
            refine(id, resolver == null ? declaredType : resolver.reconcileRuntimeType(runtimeType, declaredType),
                    runtimeType);
        } // if
    } // refineDeclared

    /**
     * Updates evidence and invalidates only contexts whose receiver evidence changed.
     *
     * @param id Guest object identity.
     * @param type New retained type.
     */
    private void update(long id, String type) {
        objectTypes.put(id, type);
        contexts.forEach(context -> context.receiverTypeChanged(id));
    } // update

    /**
     * Creates binding contexts for one suspended stack extraction phase.
     *
     * @param frames Suspended frames, newest first.
     * @return Cached bindings kept coherent with this snapshot's type evidence.
     */
    public FrameBindings frames(List<StackFrame> frames) {
        Objects.requireNonNull(resolver, "Frame bindings require source metadata");
        FrameBindings result = new FrameBindings(List.copyOf(frames));
        contexts.add(result);
        return result;
    } // frames

    /**
     * Resolves one frame, retaining lexical fallback when its stack is unavailable.
     *
     * @param target Suspended target frame.
     * @return Read-only lexical bindings, supplemented by source invocation evidence.
     */
    public Map<String, String> bindings(StackFrame target) {
        Objects.requireNonNull(resolver, "Frame bindings require source metadata");
        try {
            return frames(target.thread().frames()).bindings(target);
        } catch (IncompatibleThreadStateException | RuntimeException ignored) {
            return Collections
                    .unmodifiableMap(resolver.invocationTypes().lexicalBindings(target, resolver, objectTypes));
        } // try
    } // bindings

    /**
     * Binding cache owned by one suspended thread's extraction phase, never by the source index.
     */
    public final class FrameBindings {
        private final List<StackFrame> frames;
        private final Set<Long> receiverIds = new HashSet<>();
        private final Map<StackFrame, Map<String, String>> lexical = new HashMap<>();
        private final Map<StackFrame, Map<String, String>> qualified = new HashMap<>();
        private int next;

        /**
         * Create an extraction-local cache.
         *
         * @param frames Suspended frames, newest first.
         */
        private FrameBindings(List<StackFrame> frames) {
            this.frames = frames;
            this.next = frames.size() - 1;
            for (StackFrame frame : frames) {
                if (frame.thisObject() != null) {
                    long id = frame.thisObject().uniqueID();
                    receiverIds.add(id);
                } // if
            } // for
        } // FrameBindings

        /**
         * Invalidate dependent contexts after receiver metadata changes.
         *
         * @param id Object whose metadata may have changed.
         */
        private void receiverTypeChanged(long id) {
            // update notifies contexts only when the retained evidence changes.
            if (receiverIds.contains(id)) {
                lexical.clear();
                qualified.clear();
                next = frames.size() - 1;
            } // if
        } // receiverTypeChanged

        /**
         * Reuse contexts, computing callers before their callees.
         *
         * @param target Suspended target frame.
         * @return Target lexical bindings.
         */
        public Map<String, String> bindings(StackFrame target) {
            if (lexical.containsKey(target)) {
                return lexical.get(target);
            } // if
            int index = frames.indexOf(target);
            if (index < 0) {
                return Collections
                        .unmodifiableMap(resolver.invocationTypes().lexicalBindings(target, resolver, objectTypes));
            } // if
            while (next >= index) {
                int i = next--;
                StackFrame frame = frames.get(i);
                Map<String, String> current = resolver.invocationTypes().lexicalBindings(frame, resolver, objectTypes);
                Map<String, String> identities = new HashMap<>();
                try {
                    Optional<CallableDeclaration<?>> source = resolver.invocationTypes()
                            .declaration(frame.location().method());
                    if (source.isPresent()) {
                        ResolvedMethodLikeDeclaration resolved = InvocationTypeResolver.resolve(source.get());
                        Map<String, String> receiver = InvocationTypeResolver.receiverBindings(frame, resolver,
                                objectTypes);
                        resolved.declaringType().getTypeParameters().forEach(tp -> {
                            if (receiver.containsKey(tp.getName())) {
                                identities.put(tp.getQualifiedName(), receiver.get(tp.getName()));
                            } // if
                        });
                        if (i + 1 < frames.size() && !resolved.getTypeParameters().isEmpty()) {
                            current.putAll(resolver.invocationTypes().invocationBindings(frames.get(i + 1),
                                    frame.location().method(), qualified.get(frames.get(i + 1))));
                        } // if
                        resolved.getTypeParameters()
                                .forEach(tp -> identities.put(tp.getQualifiedName(), current.get(tp.getName())));
                    } // if
                } catch (RuntimeException ignored) {
                    // Incomplete source metadata retains lexical fallback types.
                } // try
                lexical.put(frame, Collections.unmodifiableMap(current));
                qualified.put(frame, identities);
            } // while
            return lexical.get(target);
        } // bindings
    } // FrameBindings

    /**
     * Determines whether a new type candidate is more specific than an existing type string for a given concrete
     * runtime class.
     *
     * @param newType The new type candidate.
     * @param existingType The existing type string from metadata.
     * @param runtimeClassFqn The concrete runtime class FQN.
     * @return True if newType is more specific.
     */
    private static boolean isMoreSpecific(String newType, String existingType, String runtimeClassFqn) {
        if (newType == null) {
            return false;
        } // if
        if (existingType == null) {
            return true;
        } // if
        if (newType.equals(existingType)) {
            return false;
        } // if
        boolean newMatchesRuntime = newType.startsWith(runtimeClassFqn);
        boolean existingMatchesRuntime = existingType.startsWith(runtimeClassFqn);
        if (newMatchesRuntime && !existingMatchesRuntime) {
            return true;
        } // if
        if (!newMatchesRuntime && existingMatchesRuntime) {
            return false;
        } // if
        boolean newHasGenerics = newType.contains("<");
        boolean existingHasGenerics = existingType.contains("<");
        if (newHasGenerics && !existingHasGenerics) {
            return true;
        } // if
        if (!newHasGenerics && existingHasGenerics) {
            return false;
        } // if
        if (newHasGenerics) {
            boolean existingHasWildcard = existingType.contains("?");
            boolean newHasWildcard = newType.contains("?");
            if (existingHasWildcard && !newHasWildcard) {
                return true;
            } // if
        } // if
        return false;
    } // isMoreSpecific

} // ReifiedTypeMetadata
