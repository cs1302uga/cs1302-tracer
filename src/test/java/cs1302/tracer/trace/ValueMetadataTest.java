package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;
import com.sun.jdi.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueMetadataTest {
    private static Object call(String name, Class<?>[] signature, Object... args) throws Exception {
        var method = TraceValue.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw (Exception) e.getCause(); }
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
            var value = mirror(StringReference.class, Map.of("referenceType", type,
                    "value", "text", "getValue", mirror(IntegerValue.class, Map.of("value", 1))));
            assertThat(TraceValue.fromJdiValue(null, value, Optional.empty())).isEqualTo(new TraceValue.String("text"));
        }
    }

    @Test
    void arraysAllowAbsentTypeMapAndRejectUnsupportedElements() {
        var type = mirror(ReferenceType.class, Map.of("name", "Object[]"));
        var array = mirror(ArrayReference.class, Map.of("referenceType", type, "length", 0));
        assertThat(TraceValue.fromJdiValue(null, array, Optional.empty(), null, null))
                .isEqualTo(new TraceValue.List("Object[]", List.of()));
        var unsupported = mirror(ArrayReference.class, Map.of("referenceType", type, "length", 1,
                "getValue", mirror(VoidValue.class, Map.of())));
        assertThatThrownBy(() -> TraceValue.fromJdiValue(null, unsupported, Optional.empty(), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unrecognized value");
    }

    @Test
    void colorSubclassesAndNonClassMirrorsHaveStableFallbacks() {
        var color = mirror(ClassType.class, Map.of("name", "java.awt.Color"));
        var subtype = mirror(ClassType.class, Map.of("name", "CustomColor", "superclass", color));
        var field = mirror(Field.class, Map.of());
        var oddColor = mirror(ReferenceType.class, Map.of("name", "java.awt.Color", "fieldByName", field));
        try (var session = new cs1302.tracer.execution.TraceSession(
                cs1302.tracer.execution.TraceLimits.unlimited(), cs1302.tracer.execution.InspectionPolicy.FIELDS, true)) {
            assertThat(session).isSameAs(cs1302.tracer.execution.TraceSession.current());
            assertThat(TraceValue.fromJdiValue(null, object(subtype, Map.of()), Optional.empty()))
                    .isEqualTo(new TraceValue.Color("CustomColor", "#00000000"));
            assertThat(TraceValue.fromJdiValue(null, object(oddColor, Map.of("getValue", mirror(VoidValue.class, Map.of()))), Optional.empty()))
                    .isEqualTo(new TraceValue.Color("java.awt.Color", "#00000000"));
        }
        var ordinary = mirror(ReferenceType.class, Map.of("name", "Custom", "allFields", List.of()));
        assertThat(TraceValue.fromJdiValue(null, object(ordinary, Map.of()), Optional.empty(), null, null))
                .isEqualTo(new TraceValue.Object("Custom", List.of()));
        assertThat(TraceValue.fromJdiValue(null, object(oddColor, Map.of()), Optional.empty()))
                .isEqualTo(new TraceValue.Color("java.awt.Color", "#00000000"));
    }

    @Test
    void containerPropagationSkipsUnavailableMetadataAndPrefersSpecificTypes() throws Exception {
        var signature = new Class<?>[] {ArrayReference.class, String.class, AstTypeResolver.class, Map.class};
        var resolver = new AstTypeResolver(List.of());
        var types = new HashMap<Long, String>();
        var array = mirror(ArrayReference.class, Map.of("length", 0, "getValues", List.of()));
        call("propagateContainerElements", signature, array, "List<String>", resolver, null);
        call("propagateContainerElements", signature, array, "List", resolver, types);
        call("propagateContainerElements", signature, null, "List<String>", resolver, types);
        call("propagateContainerElements", signature, array, "List<String>", null, types);
        call("propagateContainerElements", signature, array, "List<>", resolver, types);
        assertThat(types).isEmpty();
        var element = object(mirror(ReferenceType.class, Map.of("name", "Box")), Map.of());
        array = mirror(ArrayReference.class, Map.of("length", 3,
                "getValues", Arrays.asList(element, null, mirror(IntegerValue.class, Map.of()))));
        types.put(1L, "Box");
        call("propagateContainerElements", signature, array, "List<Box<String>>", resolver, types);
        assertThat(types).containsEntry(1L, "Box<String>");
        call("propagateContainerElements", signature, array, "List<Box<?>>", resolver, types);
        assertThat(types).containsEntry(1L, "Box<String>");
    }

    @Test
    void rawGenericFieldsRetainTheirErasedTypesUnlessDeclarationIsConcrete() throws Exception {
        var resolver = new AstTypeResolver(com.github.javaparser.StaticJavaParser.parse(
                "class Box<T> { T element; String text; java.util.List<T> list; }"));
        var info = resolver.getClassGenericInfo("Box");
        var signature = new Class<?>[] {Field.class, Optional.class, Map.class};
        for (String name : List.of("element", "text", "list", "unknown")) {
            var field = mirror(Field.class, Map.of("name", name, "typeName", "java.lang.Object"));
            assertThat(call("resolveFieldTypeName", signature, field, info, Map.of()))
                    .isEqualTo(name.equals("text") ? "String" : "java.lang.Object");
        }
    }

    @Test
    void fieldExtractionSupportsMissingResolverAndOptionalTypeMetadata() throws Exception {
        var signature = new Class<?>[] {ObjectReference.class, Optional.class, AstTypeResolver.class,
                Map.class, Optional.class, Map.class};
        var child = object(mirror(ReferenceType.class, Map.of("name", "Child")), Map.of("uniqueID", 2L));
        for (String fieldType : Arrays.asList(null, "Child<String>")) {
            var attributes = new HashMap<String, Object>(Map.of("name", "child", "isStatic", false, "isFinal", false));
            if (fieldType != null) attributes.put("typeName", fieldType);
            var field = mirror(Field.class, attributes);
            var type = mirror(ReferenceType.class, Map.of("allFields", List.of(field)));
            var parent = object(type, Map.of("getValue", child));
            for (Map<Long, String> types : Arrays.asList(null, new HashMap<Long, String>(), new HashMap<Long, String>(Map.of(2L, "Child")))) {
                var references = new ArrayList<ObjectReference>();
                Object result = call("extractSnapshotFields", signature, parent, Optional.of(references),
                        null, types, Optional.empty(), Map.of());
                assertThat(result).isEqualTo(List.of(new ExecutionSnapshot.Field(false, fieldType, "child", new TraceValue.Reference(2))));
                assertThat(references).containsExactly(child);
                if (types != null && fieldType != null) {
                    assertThat(types).containsEntry(2L, fieldType);
                    call("extractSnapshotFields", signature, parent, Optional.empty(), null, types, Optional.empty(), Map.of());
                    assertThat(types).containsEntry(2L, fieldType);
                }
            }
        }
        var field = mirror(Field.class, Map.of("name", "x", "isStatic", false, "typeName", "void"));
        var parent = object(mirror(ReferenceType.class, Map.of("allFields", List.of(field))),
                Map.of("getValue", mirror(VoidValue.class, Map.of())));
        assertThat(call("extractSnapshotFields", signature, parent, Optional.empty(), null, null, Optional.empty(), Map.of()))
                .isEqualTo(List.of());
    }

    @Test
    void enumMetadataAndHashEvaluationTolerateUnavailableFields() throws Exception {
        var thread = mirror(ThreadReference.class, Map.of());
        var signature = new Class<?>[] {ThreadReference.class, ObjectReference.class, ClassType.class};
        for (String mode : List.of("empty", "other", "wrong-type", "missing-value", "nonzero", "no-method", "throws", "not-class")) {
            var hash = mirror(Field.class, Map.of("name", mode.equals("other") ? "other" : "hash",
                    "typeName", mode.equals("wrong-type") ? "long" : "int"));
            var attributes = new HashMap<String, Object>(Map.of("name", "E", "isEnum", true,
                    "allFields", mode.equals("empty") ? List.of() : List.of(hash), "fieldByName", hash));
            if (!mode.equals("no-method")) attributes.put("concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of()));
            var declared = mirror(ClassType.class, attributes);
            ReferenceType type = mode.equals("not-class") ? mirror(ReferenceType.class, attributes) : declared;
            var value = mode.equals("missing-value") ? mirror(VoidValue.class, Map.of())
                    : mirror(IntegerValue.class, Map.of("value", mode.equals("nonzero") ? 1 : 0));
            var reference = object(type, Map.of("getValue", value, "invokeMethod", new InvocationException(null)));
            call("evaluateEnumHashIfNeeded", signature, thread, reference, declared);
            assertThat(call("extractEnumConstantName", new Class<?>[] {ObjectReference.class, ClassType.class}, reference, declared))
                    .isNull();
        }
        var enumType = mirror(ClassType.class, Map.of());
        assertThat(call("extractEnumConstantName", new Class<?>[] {ObjectReference.class, ClassType.class}, object(enumType, Map.of()), enumType)).isNull();
    }

    @Test
    void containerNamesFallBackToRuntimeTypeWithoutReifiedMetadata() throws Exception {
        var contract = mirror(InterfaceType.class, Map.of());
        var vm = (VirtualMachine) java.lang.reflect.Proxy.newProxyInstance(VirtualMachine.class.getClassLoader(),
                new Class<?>[] {VirtualMachine.class}, (self, method, args) ->
                        method.getName().equals("classesByName") && args[0].equals("java.util.Map") ? List.of(contract) : List.of());
        var type = mirror(ClassType.class, Map.of("name", "Container", "allInterfaces", List.of(contract),
                "concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of())));
        var array = mirror(ArrayReference.class, Map.of("length", 0));
        var collection = object(type, Map.of("virtualMachine", vm, "invokeMethod", array));
        var entries = object(type, Map.of("invokeMethod", array));
        var map = object(type, Map.of("virtualMachine", vm, "invokeMethod", entries));
        for (Map<Long, String> names : Arrays.asList(null, Map.<Long, String>of(), Map.of(1L, "Container<String>"))) {
            var result = (Optional<?>) call("handleCollection", new Class<?>[] {ThreadReference.class, ClassType.class,
                    ObjectReference.class, Optional.class, AstTypeResolver.class, Map.class},
                    null, type, collection, Optional.empty(), null, names);
            String expected = names != null && names.containsKey(1L) ? "Container<String>" : "Container";
            assertThat(result).isEqualTo(Optional.of(new TraceValue.Collection(expected, List.of())));
            assertThat(call("handleCollectionOrMap", new Class<?>[] {ThreadReference.class,
                    ObjectReference.class, Optional.class, AstTypeResolver.class, Map.class},
                    null, map, Optional.empty(), null, names))
                    .isEqualTo(Optional.of(new TraceValue.Map(expected, Map.of())));
        }
        var plainType = mirror(ReferenceType.class, Map.of("name", "Custom", "allFields", List.of()));
        assertThat(TraceValue.fromJdiValue(null, object(plainType, Map.of()), Optional.empty(), null, Map.of(1L, "Custom<String>")))
                .isEqualTo(new TraceValue.Object("Custom<String>", List.of()));
    }
}
