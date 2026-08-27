package cs1302.tracer.model;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.model.pytutor.RenderStackFrame;
import cs1302.tracer.model.pytutor.TraceStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Model Records")
public class ModelTest {

  @Test
  @DisplayName("BreakpointEntry should store line details and file correctly")
  void testBreakpointEntry() {
    BreakpointEntry entry = new BreakpointEntry(12, true, "int x = 5;");
    assertThat(entry.lineNumber()).isEqualTo(12);
    assertThat(entry.validBreakpoint()).isTrue();
    assertThat(entry.lineContent()).isEqualTo("int x = 5;");
    assertThat(entry.file()).isNull();

    BreakpointEntry entryWithFile = new BreakpointEntry(12, true, "int x = 5;", "cs1302/A.java");
    assertThat(entryWithFile.file()).isEqualTo("cs1302/A.java");
  }

  @Test
  @DisplayName("RenderStackFrame should store frame properties and file correctly")
  void testRenderStackFrame() {
    RenderStackFrame frame =
        new RenderStackFrame(
            "main:1",
            Map.of("a", 1),
            Map.of("a", Map.of("type", "int")),
            List.of("a"),
            List.of(),
            true,
            false,
            false,
            "0",
            0);

    assertThat(frame.funcName()).isEqualTo("main:1");
    assertThat(frame.encodedLocals()).containsEntry("a", 1);
    assertThat(frame.orderedVarnames()).containsExactly("a");
    assertThat(frame.parentFrameIdList()).isEmpty();
    assertThat(frame.isHighlighted()).isTrue();
    assertThat(frame.isZombie()).isFalse();
    assertThat(frame.isParent()).isFalse();
    assertThat(frame.uniqueHash()).isEqualTo("0");
    assertThat(frame.frameId()).isEqualTo(0);
    assertThat(frame.file()).isNull();

    RenderStackFrame frameWithFile =
        new RenderStackFrame(
            "main:1",
            Map.of(),
            Map.of(),
            List.of(),
            List.of(),
            true,
            false,
            false,
            "0",
            0,
            "cs1302/Driver.java");
    assertThat(frameWithFile.file()).isEqualTo("cs1302/Driver.java");
  }

  @Test
  @DisplayName("TraceStep and PyTutorTrace should assemble full trace payload")
  void testTraceStepAndPyTutorTrace() {
    TraceStep step =
        new TraceStep(
            "stdout",
            "stderr",
            "step_line",
            "main",
            5L,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of());

    assertThat(step.stdout()).isEqualTo("stdout");
    assertThat(step.stderr()).isEqualTo("stderr");
    assertThat(step.event()).isEqualTo("step_line");
    assertThat(step.funcName()).isEqualTo("main");
    assertThat(step.line()).isEqualTo(5L);
    assertThat(step.file()).isNull();

    TraceStep stepWithFile =
        new TraceStep(
            "stdout",
            "stderr",
            "step_line",
            "main",
            5L,
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            "cs1302/Main.java");
    assertThat(stepWithFile.file()).isEqualTo("cs1302/Main.java");

    PyTutorTrace root = new PyTutorTrace("class A {}", "input", List.of(step), "log");
    assertThat(root.code()).isEqualTo("class A {}");
    assertThat(root.stdin()).isEqualTo("input");
    assertThat(root.trace()).containsExactly(step);
    assertThat(root.userlog()).isEqualTo("log");
  }

  @Test
  @DisplayName("Modern model records should construct and serialize cleanly")
  void testModernModels() {
    cs1302.tracer.model.modern.Reference ref =
        new cs1302.tracer.model.modern.Reference(42L);
    assertThat(ref.ref()).isEqualTo(42L);

    cs1302.tracer.model.modern.Variable var =
        new cs1302.tracer.model.modern.Variable("count", "int", 10, true);
    assertThat(var.name()).isEqualTo("count");
    assertThat(var.type()).isEqualTo("int");
    assertThat(var.value()).isEqualTo(10);
    assertThat(var.isFinal()).isTrue();

    cs1302.tracer.model.modern.HeapObject obj =
        cs1302.tracer.model.modern.HeapObject.ofObject(1L, "MyClass", List.of(var));
    assertThat(obj.id()).isEqualTo(1L);
    assertThat(obj.type()).isEqualTo("MyClass");
    assertThat(obj.kind()).isEqualTo("object");
    assertThat(obj.fields()).containsExactly(var);

    cs1302.tracer.model.modern.HeapObject arr =
        cs1302.tracer.model.modern.HeapObject.ofArray(2L, "int[]", List.of(1, 2));
    assertThat(arr.kind()).isEqualTo("array");
    assertThat(arr.elements()).containsExactly(1, 2);

    cs1302.tracer.model.modern.HeapObject str =
        cs1302.tracer.model.modern.HeapObject.ofString(3L, "hello");
    assertThat(str.kind()).isEqualTo("string");
    assertThat(str.value()).isEqualTo("hello");

    cs1302.tracer.model.modern.HeapObject box =
        cs1302.tracer.model.modern.HeapObject.ofBox(4L, "java.lang.Integer", 42);
    assertThat(box.kind()).isEqualTo("box");

    cs1302.tracer.model.modern.HeapObject lam =
        cs1302.tracer.model.modern.HeapObject.ofLambda(5L, "lambda", "() -> {}");
    assertThat(lam.kind()).isEqualTo("lambda");
    assertThat(lam.sam()).isEqualTo("() -> {}");

    cs1302.tracer.model.modern.StackFrame frame =
        new cs1302.tracer.model.modern.StackFrame(
            "main", 10, "Driver.java", true, ref, List.of(var));
    assertThat(frame.methodName()).isEqualTo("main");
    assertThat(frame.line()).isEqualTo(10);
    assertThat(frame.file()).isEqualTo("Driver.java");
    assertThat(frame.isHighlighted()).isTrue();
    assertThat(frame.thisObject()).isEqualTo(ref);
    assertThat(frame.locals()).containsExactly(var);

    cs1302.tracer.model.modern.Step step =
        new cs1302.tracer.model.modern.Step(
            1,
            10,
            "Driver.java",
            "step_line",
            "main",
            List.of(frame),
            List.of(var),
            Map.of("1", obj),
            "out",
            "err");
    assertThat(step.step()).isEqualTo(1);
    assertThat(step.method()).isEqualTo("main");
    assertThat(step.stdout()).isEqualTo("out");
    assertThat(step.stderr()).isEqualTo("err");

    cs1302.tracer.model.modern.Trace trace1 =
        new cs1302.tracer.model.modern.Trace("code", List.of(step));
    assertThat(trace1.format()).isEqualTo("modern");
    assertThat(trace1.steps()).containsExactly(step);

    cs1302.tracer.model.modern.Trace trace2 =
        new cs1302.tracer.model.modern.Trace("code", Map.of(1, step));
    assertThat(trace2.breakpoints()).containsKey(1);
  }
}
