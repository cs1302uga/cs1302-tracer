package cs1302.tracer.internal.trace;

import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import static org.assertj.core.api.Assertions.*;
import com.sun.jdi.*;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.internal.execution.TraceSession;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class HeapInspectionTest {
    private static ObjectReference node(long id, Map<String, ObjectReference> children, List<Long> observed) {
        var type = (ReferenceType) Proxy.newProxyInstance(ReferenceType.class.getClassLoader(),
                new Class<?>[] { ReferenceType.class }, (self, method, arguments) -> switch (method.getName()) {
                case "name" -> "Node";
                case "allFields" -> {
                    observed.add(id);
                    yield children.keySet().stream().map(name -> mirror(Field.class,
                            Map.of("name", name, "typeName", "Node", "isStatic", false, "isFinal", false))).toList();
                }
                default -> null;
                });
        return (ObjectReference) Proxy.newProxyInstance(ObjectReference.class.getClassLoader(),
                new Class<?>[] { ObjectReference.class }, (self, method, arguments) -> switch (method.getName()) {
                case "uniqueID" -> id;
                case "referenceType", "type" -> type;
                case "getValue" -> children.get(((Field) arguments[0]).name());
                default -> null;
                });
    }

    @Test
    void rootsAndDescendantsShareIdentityAndFollowFifoInspectionAcrossCycles() {
        var observed = new ArrayList<Long>();
        var firstFields = new LinkedHashMap<String, ObjectReference>();
        var childFields = new LinkedHashMap<String, ObjectReference>();
        var first = node(1, firstFields, observed);
        var child = node(2, childFields, observed);
        var secondRoot = node(3, Map.of(), observed);
        firstFields.put("child", child);
        childFields.put("parent", first);
        var arrayType = mirror(ReferenceType.class, Map.of("name", "Node[]"));
        var array = mirror(ArrayReference.class, Map.of("uniqueID", 4L, "referenceType", arrayType,
                "length", 3, "getValue", child));
        var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
        inspection.encounter(null);
        inspection.encounter(first);
        inspection.encounter(secondRoot);
        inspection.encounter(array);
        inspection.encounter(first);
        var heap = inspection.finish();
        assertThat(observed).containsExactly(1L, 3L, 2L);
        assertThat(heap).containsOnlyKeys(1L, 2L, 3L, 4L);
        assertThat(((TraceValue.List) heap.get(4L)).value())
                .isEqualTo(List.of(new TraceValue.Reference(2L), new TraceValue.Reference(2L), new TraceValue.Reference(2L)));
    }

    @Test
    void cyclicArrayIsCapturedOnceWithoutRecursing() {
        var type = mirror(ReferenceType.class, Map.of("name", "Object[]"));
        var attributes = new HashMap<String, Object>(Map.of("uniqueID", 1L, "referenceType", type, "length", 1));
        var array = mirror(ArrayReference.class, attributes);
        attributes.put("getValue", array);
        var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
        inspection.encounter(array);
        assertThat(inspection.finish()).containsOnlyKeys(1L).extractingByKey(1L)
                .isEqualTo(new TraceValue.List("Object[]", List.of(new TraceValue.Reference(1L))));
    }

    @Test
    void sourceLambdaOverridesQueuedInspectionAndLaterSourceObservationsReplaceEarlierOnes() {
        var reference = mirror(ObjectReference.class,
                Map.of("uniqueID", 1L, "referenceType", new AssertionError("Source lambda must skip JDI inspection")));
        try (var session = new TraceSession(TraceLimits.unlimited().toBuilder().heapObjects(1).build(),
                InspectionPolicy.FIELDS, true)) {
            session.beginSnapshot();
            var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
            inspection.encounter(reference);
            inspection.sourceLambda(reference, "first");
            inspection.sourceLambda(reference, "second");
            assertThat(inspection.finish()).containsExactly(Map.entry(1L, new TraceValue.Lambda("second")));
            assertThat(session.stopReason()).isNull();
        }
    }

    @Test
    void exactlyAtObjectBudgetAllowsAliasesAndOneMoreIdentityStopsBeforeInspection() {
        var observed = new ArrayList<Long>();
        var first = node(1, Map.of(), observed);
        var second = node(2, Map.of(), observed);
        try (var session = new TraceSession(TraceLimits.unlimited().toBuilder().heapObjects(1).build(),
                InspectionPolicy.FIELDS, true)) {
            session.beginSnapshot();
            var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
            inspection.encounter(first);
            inspection.encounter(first);
            assertThat(inspection.finish()).containsOnlyKeys(1L);
            assertThat(session.stopReason()).isNull();
        }
        observed.clear();
        try (var session = new TraceSession(TraceLimits.unlimited().toBuilder().heapObjects(1).build(),
                InspectionPolicy.FIELDS, true)) {
            session.beginSnapshot();
            var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
            inspection.encounter(first);
            assertThatThrownBy(() -> inspection.encounter(second)).isInstanceOf(TraceSession.Stopped.class);
            assertThat(session.stopReason()).isEqualTo("heap_limit");
            assertThat(observed).isEmpty();
            assertThat(session.snapshots()).isEmpty();
        }
    }

    @Test
    void repeatedLambdaTextStillConsumesElementBudgetBeforeReplacingItsValue() {
        var reference = mirror(ObjectReference.class, Map.of("uniqueID", 1L));
        try (var session = new TraceSession(TraceLimits.unlimited().toBuilder().elements(3).build(),
                InspectionPolicy.FIELDS, true)) {
            session.beginSnapshot();
            var inspection = new HeapInspection(null, new ReifiedTypeMetadata(null));
            inspection.sourceLambda(reference, "abc");
            assertThatThrownBy(() -> inspection.sourceLambda(reference, "d")).isInstanceOf(TraceSession.Stopped.class);
            assertThat(session.stopReason()).isEqualTo("element_limit");
            assertThat(session.snapshots()).isEmpty();
        }
    }

    @Test
    void independentSnapshotsDoNotRetainEarlierHeapOrTypeEvidence() {
        var firstMetadata = new ReifiedTypeMetadata(null);
        firstMetadata.recordFallback(1L, "Node<String>");
        var first = new HeapInspection(null, firstMetadata);
        first.encounter(node(1, Map.of(), new ArrayList<>()));
        assertThat(((TraceValue.Object) first.finish().get(1L)).classFqn()).isEqualTo("Node<String>");
        var second = new HeapInspection(null, new ReifiedTypeMetadata(null));
        second.encounter(node(1, Map.of(), new ArrayList<>()));
        assertThat(((TraceValue.Object) second.finish().get(1L)).classFqn()).isEqualTo("Node");
    }
}
