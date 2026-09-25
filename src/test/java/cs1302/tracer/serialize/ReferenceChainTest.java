package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.TraceValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferenceChainTest {
  @Test
  void handlesLongAndCyclicReferenceChainsWhenSamplingTypes() {
    for (boolean cyclic : List.of(false, true)) {
      Map<Long, TraceValue> heap = new HashMap<>();
      heap.put(0L, new TraceValue.List("java.util.ArrayList", List.of(new TraceValue.Reference(1L))));
      for (long i = 1; i < 10000; i++) {
        heap.put(i, new TraceValue.Reference(i + 1));
      }
      heap.put(10000L, cyclic ? new TraceValue.Reference(1L) : new TraceValue.String("end"));
      var snapshot = new ExecutionSnapshot(List.of(), List.of(), heap, new byte[0], new byte[0]);
      var step = new PyTutorSerializer(false, false, false).createTraceStep(snapshot);
      assertThat(step.heap()).hasSize(10001);
    }
  }
}
