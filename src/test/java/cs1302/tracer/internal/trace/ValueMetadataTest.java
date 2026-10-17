package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import static cs1302.tracer.internal.trace.JdiValueContractTest.inspect;
import com.sun.jdi.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueMetadataTest {
    private static ReifiedTypeMetadata metadata(AstTypeResolver resolver, Map<Long, String> initial) {
        var metadata = new ReifiedTypeMetadata(resolver);
        initial.forEach(metadata::recordFallback);
        return metadata;
    }

    private static ObjectReference object(ReferenceType type, Map<String, Object> extras) {
        var values = new HashMap<String, Object>(extras);
        values.put("referenceType", type);
        values.put("type", type);
        values.putIfAbsent("uniqueID", 1L);
        return mirror(ObjectReference.class, values);
    }

    @Test
    void stringConversionToleratesUnknownBackingLayout() {
        var field = mirror(Field.class, Map.of());
        for (var type : List.of(mirror(ReferenceType.class, Map.of()),
                mirror(ReferenceType.class, Map.of("fieldByName", field)))) {
            var value = mirror(StringReference.class, Map.of("uniqueID", 1L, "referenceType", type, "value", "text",
                    "getValue", mirror(IntegerValue.class, Map.of("value", 1))));
            assertThat(inspect(value)).isEqualTo(new TraceValue.String("text"));
        }
    }

    @Test
    void arraysUseRuntimeOrRefinedNamesAndRejectUnsupportedElements() {
        var type = mirror(ReferenceType.class, Map.of("name", "Object[]"));
        var array = mirror(ArrayReference.class, Map.of("uniqueID", 1L, "referenceType", type, "length", 0));
        assertThat(inspect(array)).isEqualTo(new TraceValue.List("Object[]", List.of()));
        assertThat(inspect(array, metadata(null, Map.of(1L, "String[]"))))
                .isEqualTo(new TraceValue.List("String[]", List.of()));
        var unsupported = mirror(ArrayReference.class, Map.of("uniqueID", 1L, "referenceType", type,
                "length", 1, "getValue", mirror(VoidValue.class, Map.of())));
        assertThatThrownBy(() -> inspect(unsupported)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized value");
    }

    @Test
    void colorSubclassesAndNonClassMirrorsHaveStableFallbacks() {
        var color = mirror(ClassType.class, Map.of("name", "java.awt.Color"));
        var subtype = mirror(ClassType.class, Map.of("name", "CustomColor", "superclass", color));
        var field = mirror(Field.class, Map.of());
        var oddColor = mirror(ReferenceType.class, Map.of("name", "java.awt.Color", "fieldByName", field));
        try (var session = new cs1302.tracer.internal.execution.TraceSession(cs1302.tracer.api.TraceLimits.unlimited(),
                cs1302.tracer.api.InspectionPolicy.FIELDS, true)) {
            assertThat(session).isSameAs(cs1302.tracer.internal.execution.TraceSession.current());
            assertThat(inspect(object(subtype, Map.of()))).isEqualTo(new TraceValue.Color("CustomColor", "#00000000"));
            assertThat(inspect(object(oddColor, Map.of("getValue", mirror(VoidValue.class, Map.of())))))
                    .isEqualTo(new TraceValue.Color("java.awt.Color", "#00000000"));
        }
        var ordinary = mirror(ReferenceType.class, Map.of("name", "Custom", "allFields", List.of()));
        assertThat(inspect(object(ordinary, Map.of()))).isEqualTo(new TraceValue.Object("Custom", List.of()));
        assertThat(inspect(object(oddColor, Map.of()))).isEqualTo(new TraceValue.Color("java.awt.Color", "#00000000"));
    }

    private static ObjectReference container(String kind, ArrayReference array) {
        var contract = mirror(InterfaceType.class, Map.of());
        var vm = (VirtualMachine) Proxy.newProxyInstance(VirtualMachine.class.getClassLoader(),
                new Class<?>[] { VirtualMachine.class }, (self, method, args) -> {
                    if (!method.getName().equals("classesByName"))
                        return null;
                    return args[0].equals("java.util." + kind)
                            || kind.equals("List") && args[0].equals("java.util.Collection")
                            ? List.of(contract) : List.of();
                });
        var type = mirror(ClassType.class, Map.of("name", "Container", "signature", "LContainer;",
                "allInterfaces", List.of(contract), "concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of()),
                "isEnum", false, "allFields", List.of()));
        var values = new HashMap<String, Object>(Map.of("virtualMachine", vm));
        if (array != null)
            values.put("invokeMethod", array);
        var collection = object(type, values);
        if (!kind.equals("Map"))
            return collection;
        var entries = object(type, values);
        return object(type, Map.of("virtualMachine", vm, "invokeMethod", entries));
    }

    private static ArrayReference elements(List<? extends Value> values) {
        return (ArrayReference) Proxy.newProxyInstance(ArrayReference.class.getClassLoader(),
                new Class<?>[] { ArrayReference.class }, (self, method, args) -> switch (method.getName()) {
                case "length" -> values.size();
                case "getValues" -> values;
                case "getValue" -> values.get((int) args[0]);
                default -> null;
                });
    }

    @Test
    void containerPropagationRefinesCapturedElementsAndPrefersSpecificTypes() {
        var resolver = new AstTypeResolver(List.of());
        var child = object(mirror(ReferenceType.class, Map.of("name", "Box", "allFields", List.of())),
                Map.of("uniqueID", 2L));
        var values = elements(Arrays.asList(child, null, mirror(IntegerValue.class, Map.of("value", 3))));
        var types = metadata(resolver, Map.of(1L, "Container<Box<String>>", 2L, "Box"));
        var inspection = new HeapInspection(null, types);
        inspection.encounter(container("List", values));
        var heap = inspection.finish();
        assertThat(heap.get(1L)).isEqualTo(new TraceValue.List("Container<Box<String>>",
                List.of(new TraceValue.Reference(2L), new TraceValue.Null(), new TraceValue.Primitive.Integer(3))));
        assertThat(((TraceValue.Object) heap.get(2L)).classFqn()).isEqualTo("Box<String>");
        assertThat(types.typeOf(2L)).isEqualTo("Box<String>");
        types.recordFallback(3L, "Box<String>");
        var wildcardChild = object(mirror(ReferenceType.class, Map.of("name", "Box", "allFields", List.of())),
                Map.of("uniqueID", 3L));
        var wildcardTypes = metadata(resolver, Map.of(1L, "Container<Box<?>>", 3L, "Box<String>"));
        assertThat(inspect(container("Collection", elements(List.of(wildcardChild))), wildcardTypes))
                .isInstanceOf(TraceValue.Collection.class);
        assertThat(wildcardTypes.typeOf(3L)).isEqualTo("Box<String>");
    }

    @Test
    void containersTolerateUnavailableSourceEvidenceAndUnparameterizedNames() {
        var empty = elements(List.of());
        var resolver = new AstTypeResolver(List.of());
        for (var types : List.of(new ReifiedTypeMetadata(null), new ReifiedTypeMetadata(resolver),
                metadata(resolver, Map.of(1L, "Container<>")), metadata(null, Map.of(1L, "Container<String>")))) {
            String name = types.typeOf(1L) == null ? "Container" : types.typeOf(1L);
            assertThat(inspect(container("Collection", empty), types))
                    .isEqualTo(new TraceValue.Collection(name, List.of()));
            assertThat(inspect(container("Map", empty), types)).isEqualTo(new TraceValue.Map(name, Map.of()));
        }
        var types = metadata(resolver, Map.of(1L, "Container<String>"));
        assertThatThrownBy(() -> inspect(container("Collection", null), types))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rawGenericFieldsRetainErasureUntilConcreteBindingsAreAvailable() {
        var resolver = new AstTypeResolver(com.github.javaparser.StaticJavaParser
                .parse("class Box<T> { T element; String text; java.util.List<T> list; }"));
        var fields = List.of("element", "text", "list", "unknown").stream().map(name -> mirror(Field.class,
                Map.of("name", name, "typeName", "java.lang.Object", "isStatic", false, "isFinal", false))).toList();
        var type = mirror(ReferenceType.class, Map.of("name", "Box", "allFields", fields));
        var root = object(type, Map.of());
        var raw = (TraceValue.Object) inspect(root, new ReifiedTypeMetadata(resolver));
        assertThat(raw.fields()).extracting(ExecutionSnapshot.Field::typeName)
                .containsExactly("java.lang.Object", "String", "java.lang.Object", "java.lang.Object");
        var refined = (TraceValue.Object) inspect(root, metadata(resolver, Map.of(1L, "Box<String>")));
        assertThat(refined.fields()).extracting(ExecutionSnapshot.Field::typeName)
                .containsExactly("String", "String", "java.util.List<String>", "java.lang.Object");
    }

    @Test
    void fieldDiscoveryUsesSharedTypeEvidenceAndPreservesAbsentDeclaredNames() {
        var child = object(mirror(ReferenceType.class, Map.of("name", "Child", "allFields", List.of())),
                Map.of("uniqueID", 2L));
        for (String fieldType : Arrays.asList(null, "Child<String>")) {
            var attributes = new HashMap<String, Object>(Map.of("name", "child", "isStatic", false, "isFinal", false));
            if (fieldType != null)
                attributes.put("typeName", fieldType);
            var field = mirror(Field.class, attributes);
            var root = object(mirror(ReferenceType.class, Map.of("name", "Parent", "allFields", List.of(field))),
                    Map.of("getValue", child));
            var types = metadata(null, Map.of(2L, "Child"));
            var inspection = new HeapInspection(null, types);
            inspection.encounter(root);
            var heap = inspection.finish();
            assertThat(((TraceValue.Object) heap.get(1L)).fields()).containsExactly(
                    new ExecutionSnapshot.Field(false, fieldType, "child", new TraceValue.Reference(2L)));
            assertThat(((TraceValue.Object) heap.get(2L)).classFqn())
                    .isEqualTo(fieldType == null ? "Child" : fieldType);
        }
        var field = mirror(Field.class, Map.of("name", "x", "isStatic", false, "isFinal", false, "typeName", "void"));
        var root = object(mirror(ReferenceType.class, Map.of("name", "Parent", "allFields", List.of(field))),
                Map.of("getValue", mirror(VoidValue.class, Map.of())));
        assertThat(inspect(root)).isEqualTo(new TraceValue.Object("Parent", List.of()));
    }

    @Test
    void enumMetadataAndHashEvaluationTolerateUnavailableFields() {
        var thread = mirror(ThreadReference.class, Map.of());
        for (String mode : List.of("empty", "other", "wrong-type", "missing-value", "nonzero", "no-method", "throws",
                "not-class", "name-unavailable")) {
            var hash = mirror(Field.class, Map.of("name", mode.equals("other") ? "other" : "hash", "typeName",
                    mode.equals("wrong-type") ? "long" : "int", "isStatic", true));
            var attributes = new HashMap<String, Object>(Map.of("name", "E", "signature", "LE;", "isEnum", true,
                    "allInterfaces", List.of(), "allFields", mode.equals("empty") ? List.of() : List.of(hash),
                    "fieldByName", hash));
            if (mode.equals("name-unavailable"))
                attributes.remove("fieldByName");
            if (!mode.equals("no-method"))
                attributes.put("concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of()));
            ReferenceType type = mode.equals("not-class") ? mirror(ReferenceType.class, attributes)
                    : mirror(ClassType.class, attributes);
            var value = mode.equals("missing-value") ? mirror(VoidValue.class, Map.of())
                    : mirror(IntegerValue.class, Map.of("value", mode.equals("nonzero") ? 1 : 0));
            var root = object(type, Map.of("getValue", value, "invokeMethod", new InvocationException(null),
                    "virtualMachine", mirror(VirtualMachine.class, Map.of("classesByName", List.of()))));
            var inspection = new HeapInspection(thread, new ReifiedTypeMetadata(null));
            inspection.encounter(root);
            assertThat(inspection.finish().get(1L)).isEqualTo(new TraceValue.Object("E", List.of()));
        }
    }
}
