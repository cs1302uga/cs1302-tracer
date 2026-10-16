package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.MethodExitEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputObservationTest {
    @Test
    void unavailableInitialInputIdentityIsResolvedOnLaterReads() {
        var input = mirror(ObjectReference.class, Map.of());
        var metadata = new java.util.HashMap<String, Object>();
        var vm = mirror(VirtualMachine.class, metadata);
        var observation = new InputObservation("x");
        observation.start(vm);
        var system = mirror(ReferenceType.class,
                Map.of("fieldByName", mirror(Field.class, Map.of()), "getValue", input));
        metadata.put("classesByName", List.of(system));
        var method = mirror(Method.class, Map.of("name", "read", "declaringType",
                mirror(ReferenceType.class, Map.of("name", "java.io.InputStream")), "argumentTypeNames", List.of()));
        var event = mirror(MethodExitEvent.class, Map.of("method", method, "virtualMachine", vm,
                "thread", mirror(ThreadReference.class, Map.of("frames",
                        List.of(mirror(StackFrame.class, Map.of("thisObject", input))))),
                "returnValue", mirror(IntegerValue.class, Map.of("value", 120))));
        observation.record(event);
        assertThat(observation.consumed()).isEqualTo("x");
        assertThat(observation.offset()).isEqualTo(1);
    }

    @Test
    void rawReadsAdvanceOnlyCompleteUtf8CharactersAndJobsStartFresh() {
        var input = mirror(ObjectReference.class, Map.of());
        var system = mirror(ReferenceType.class,
                Map.of("fieldByName", mirror(Field.class, Map.of()), "getValue", input));
        var vm = mirror(VirtualMachine.class, Map.of("classesByName", List.of(system)));
        var method = mirror(Method.class, Map.of("name", "read", "declaringType",
                mirror(ReferenceType.class, Map.of("name", "java.io.InputStream")), "argumentTypeNames", List.of()));
        var frame = mirror(StackFrame.class, Map.of("thisObject", input));
        var event = mirror(MethodExitEvent.class, Map.of("method", method, "virtualMachine", vm,
                "thread", mirror(ThreadReference.class, Map.of("frames", List.of(frame))),
                "returnValue", mirror(IntegerValue.class, Map.of("value", 195))));
        var observation = new InputObservation("éx");
        observation.start(vm);
        observation.record(event);
        assertThat(observation.consumed()).isEmpty();
        observation.record(event);
        assertThat(observation.consumed()).isEqualTo("é");
        assertThat(observation.offset()).isEqualTo(1);
        var nextJob = new InputObservation("éx");
        nextJob.start(vm);
        assertThat(nextJob.consumed()).isEmpty();
        assertThat(nextJob.offset()).isZero();
    }
}
