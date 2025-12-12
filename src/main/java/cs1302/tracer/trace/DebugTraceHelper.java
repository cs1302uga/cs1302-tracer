package cs1302.tracer.trace;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedLambdaConstraintType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.MethodExitRequest;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** A collection of methods that are used to generate a debug trace. */
public class DebugTraceHelper {

  /** A simple JavaParser object so we don't have to make a new one every time we do parsing. */
  private static final JavaParser simpleJavaParser =
      new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));

  /**
   * Take snapshots of a program's execution state at the given breakpoints.
   *
   * @param compilationResult A properly filled CompilationResult (probably from a call to
   *     CompilationHelper.compile()).
   * @param breakPoints The source line numbers that you want to take snapshots at. if it contains
   *     the special value -1, is null, or is empty, a snapshot will be taken at the time the main
   *     method exits.
   * @param parsedSource Parsed source code for the compiled program.
   * @return A mapping from breakpoint line numbers to a list of execution snapshots. If a snapshot
   *     was taken at the end of main (as described above), it is provided under the special key -1.
   *     The list contains a snapshot for each time the breakpoint was reached, with the first
   *     element being the first time and the last element being the last time.
   */
  public static Map<Integer, List<ExecutionSnapshot>> trace(
      CompilationResult compilationResult,
      Collection<Integer> breakPoints,
      CompilationUnit parsedSource)
      throws IOException,
          IllegalConnectorArgumentsException,
          VMStartException,
          InterruptedException,
          IncompatibleThreadStateException,
          AbsentInformationException,
          ClassNotLoadedException {

    boolean snapMainEnd = breakPoints == null || breakPoints.isEmpty() || breakPoints.contains(-1);

    Map<Integer, List<ExecutionSnapshot>> snapshots = new HashMap<>();

    VirtualMachine vm = startVmWithCprs(compilationResult);

    ByteArrayOutputStream vmErrSink = new ByteArrayOutputStream();
    {
      InputStream vmErrSource = vm.process().getErrorStream();
      Thread.ofVirtual()
          .start(
              () -> {
                while (true) {
                  try {
                    int vmErrData = vmErrSource.read();
                    if (vmErrData == -1) {
                      break;
                    }
                    synchronized (vmErrSink) {
                      vmErrSink.write(vmErrData);
                    }
                  } catch (IOException ioe) {
                    break;
                  }
                }
              });
    }

    ByteArrayOutputStream vmOutSink = new ByteArrayOutputStream();
    {
      InputStream vmOutSource = vm.process().getInputStream();
      Thread.ofVirtual()
          .start(
              () -> {
                while (true) {
                  try {
                    int vmOutData = vmOutSource.read();
                    if (vmOutData == -1) {
                      break;
                    }
                    synchronized (vmOutSink) {
                      vmOutSink.write(vmOutData);
                    }
                  } catch (IOException ioe) {
                    break;
                  }
                }
              });
    }

    if (snapMainEnd) {
      // fire an event when exiting main
      MethodExitRequest methodExitRequest = vm.eventRequestManager().createMethodExitRequest();
      methodExitRequest.addClassFilter(compilationResult.mainClass());
      methodExitRequest.enable();
    }

    HashSet<ReferenceType> loadedClasses = new HashSet<>();

    boolean endEventLoop = false;
    while (!endEventLoop) {
      for (Event event : vm.eventQueue().remove()) {
        switch (event) {
          case ClassPrepareEvent cpe -> {
            if (compilationResult.compiledClassNames().contains(cpe.referenceType().name())) {
              if (breakPoints != null) {
                for (int breakLine : breakPoints) {
                  List<Location> locations = cpe.referenceType().locationsOfLine(breakLine);
                  if (locations.isEmpty()) {
                    break;
                  }
                  vm.eventRequestManager().createBreakpointRequest(locations.get(0)).enable();
                }
              }
              loadedClasses.add(cpe.referenceType());
            }
          }
          case BreakpointEvent bpe -> {
            Location breakLocation = bpe.location();
            if (compilationResult
                .compiledClassNames()
                .contains(breakLocation.declaringType().name())) {

              Integer line = breakLocation.lineNumber();
              ExecutionSnapshot snapshot =
                  snapshotTheWorld(bpe.thread(), loadedClasses, vmOutSink, vmErrSink, parsedSource);

              snapshots.computeIfAbsent(line, ArrayList<ExecutionSnapshot>::new).add(snapshot);
            }
          }
          case MethodExitEvent mee -> {
            Method method = mee.method();
            // this is the JNI signature for a method with one string array parameter that
            // returns void. for details, see
            // https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/types.html#type_signatures
            String mainJniSignature = "([Ljava/lang/String;)V";
            boolean isMain =
                method.isPublic()
                    && method.isStatic()
                    && method.name().equals("main")
                    && method.signature().equals(mainJniSignature);

            if (isMain && (snapMainEnd || snapshots.isEmpty())) {
              ExecutionSnapshot snapshot =
                  snapshotTheWorld(mee.thread(), loadedClasses, vmOutSink, vmErrSink, parsedSource);

              snapshots.put(-1, List.of(snapshot));
            }
          }
          case VMDeathEvent vde -> {
            endEventLoop = true;
            break;
          }
          default -> {}
        }

        vm.resume();
      }
    }

    return snapshots;
  }

  /**
   * Take a snapshot of a program's execution state just before the main method returns.
   *
   * @param compilationResult A properly filled CompilationResult (probably from a call to
   *     CompilationHelper.compile()).
   * @param parsedSource Parsed source code for the compiled program.
   * @return An execution snapshot taken at the end of the main method, or null if execution
   *     terminated before the main method ended.
   */
  public static ExecutionSnapshot trace(
      CompilationResult compilationResult, CompilationUnit parsedSource)
      throws IOException,
          IllegalConnectorArgumentsException,
          VMStartException,
          InterruptedException,
          IncompatibleThreadStateException,
          AbsentInformationException,
          ClassNotLoadedException {
    return trace(compilationResult, null, parsedSource).get(-1).getLast();
  } // trace

  /**
   * Get the lines of a Java program that are valid breakpoint targets.
   *
   * @param compilationResult The CompilationResult of the program that you want to find the valid
   *     breakpoints for.
   * @return The lines of compilationResult that are valid breakpoint targets.
   */
  public static HashSet<Integer> getValidBreakpointLines(CompilationResult compilationResult)
      throws IOException,
          IllegalConnectorArgumentsException,
          VMStartException,
          InterruptedException,
          AbsentInformationException {

    VirtualMachine vm = startVmWithCprs(compilationResult);

    HashSet<Integer> validBreakLines = new HashSet<>();
    HashSet<String> compiledClasses = new HashSet<>(compilationResult.compiledClassNames());

    while (!compiledClasses.isEmpty()) {
      for (Event event : vm.eventQueue().remove()) {
        switch (event) {
          case ClassPrepareEvent cpe -> {
            validBreakLines.addAll(
                cpe.referenceType().allLineLocations().stream()
                    .map(ll -> ll.lineNumber())
                    .toList());
            compiledClasses.remove(cpe.referenceType().name());
          } // case ClassPrepareEvent
          case VMDeathEvent vde -> {
            return validBreakLines;
          } // case VMDeathEvent
          default -> {} // default
        } // switch
        vm.resume();
      } // for
    } // while

    return validBreakLines;
  } // getValidBreakpointLines

  /**
   * Start a JDI VM prepopulated with ClassPrepareRequests for the compiledClassNames present in
   * compilationResult.
   *
   * @param compilationResult The CompilationResult that contains the classes for which class
   *     preparation requests should be registered.
   * @return The VirtualMachine for the launched VM.
   */
  private static VirtualMachine startVmWithCprs(CompilationResult compilationResult)
      throws IOException, IllegalConnectorArgumentsException, VMStartException {

    LaunchingConnector launchingConnector = Bootstrap.virtualMachineManager().defaultConnector();

    Map<String, Connector.Argument> env = launchingConnector.defaultArguments();

    env.get("main").setValue(compilationResult.mainClass());
    env.get("options").setValue("-classpath " + compilationResult.classPath());

    VirtualMachine vm = launchingConnector.launch(env);

    for (String className : compilationResult.compiledClassNames()) {
      // fire an event each time one of our classes is prepared, mostly as a
      // springboard for setting up further eventrequests
      ClassPrepareRequest classPrepareRequest =
          vm.eventRequestManager().createClassPrepareRequest();
      classPrepareRequest.addClassFilter(className);
      classPrepareRequest.enable();
    } // for

    return vm;
  } // startVmWithCprs

  /**
   * Convert a lambda expression in the AST into an implementation of the corresponding functional
   * interface's single abstract method.
   *
   * @param lambda The lambda expression to attempt to convert.
   * @return A string containing a valid method implementation of this lambda expression, or empty
   *     if conversion was not possible.
   */
  private static Optional<String> tryImplementLambdaSam(LambdaExpr lambda) {
    Optional<MethodUsage> maybeSam =
        FunctionalInterfaceLogic.getFunctionalMethod(lambda.calculateResolvedType());

    if (maybeSam.isEmpty()) {
      return Optional.empty();
    }

    MethodUsage sam = maybeSam.get();
    StringBuilder sb = new StringBuilder();

    String resolvedReturnType =
        lambda.calculateResolvedType().asReferenceType().getTypeParametersMap().stream()
            .filter(p -> sam.returnType().isTypeVariable())
            .filter(p -> p.a.getName().equals(sam.returnType().asTypeVariable().describe()))
            .map(p -> p.b.describe())
            .findFirst()
            .orElse(sam.returnType().describe());

    sb.append(resolvedReturnType);
    sb.append(" ");
    sb.append(sam.getName());

    // to use the parameter name used in the method declaration, use
    // m.getDeclaration().getParam(i).getName()
    sb.append(
        IntStream.range(0, sam.getDeclaration().getNumberOfParams())
            .mapToObj(
                i ->
                    String.format(
                        "%s %s",
                        switch (lambda.getParameter(i).resolve().getType()) {
                          case ResolvedLambdaConstraintType c -> c.getBound().describe();
                          case ResolvedType d -> d.describe();
                        },
                        lambda.getParameter(i).getName()))
            .collect(Collectors.joining(", ", "(", ")")));

    if (lambda.getBody() instanceof ExpressionStmt e) {
      sb.append("{\n");
      if (!resolvedReturnType.equals("void")) {
        sb.append("return ");
      }
      sb.append(e);
      sb.append("}");
    } else if (lambda.getBody() instanceof BlockStmt b) {
      sb.append(b);
    }

    // pretty-print constructed method
    return simpleJavaParser.parseMethodDeclaration(sb.toString()).getResult().map(Object::toString);
  }

  /**
   * Take a snapshot of a thread's memory state at this instant of execution.
   *
   * @param mainThread A suspended thread that you want to take a snapshot of.
   * @param loadedClasses The loaded classes whose static fields you want included in the snapshot.
   * @param vmOut An output stream containing the VM's standard output.
   * @param vmErr An output stream containing the VM's standard error.
   * @param parsedSource Parsed source code for the compiled program.
   * @return An execution snapshot of the thread's memory state at the time of calling.
   */
  private static ExecutionSnapshot snapshotTheWorld(
      ThreadReference mainThread,
      Iterable<ReferenceType> loadedClasses,
      ByteArrayOutputStream vmOut,
      ByteArrayOutputStream vmErr,
      CompilationUnit parsedSource)
      throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

    List<ObjectReference> heapReferencesToWalk = new ArrayList<>();
    Map<Long, TraceValue> heap = new HashMap<>();

    /** Mapping from a variable name to a lambda implementation. */
    record VarLambda(String variableName, Optional<String> lambdaImplementation) {}

    Map<String, Map<String, String>> lambdaMethodVariables =
        parsedSource.findAll(MethodDeclaration.class).stream()
            .collect(
                Collectors.toMap(
                    m ->
                        m.resolve()
                            .getQualifiedSignature()
                            .replaceAll("\\.\\.\\.", "[]")
                            .replaceAll("\\s", ""),
                    m ->
                        m.findAll(VariableDeclarator.class).stream()
                            .filter(
                                d -> d.getInitializer().map(Expression::isLambdaExpr).orElse(false))
                            .map(
                                d ->
                                    new VarLambda(
                                        d.getNameAsString(),
                                        tryImplementLambdaSam(
                                            d.getInitializer().get().asLambdaExpr())))
                            .filter(d -> d.lambdaImplementation.isPresent())
                            .collect(
                                Collectors.toMap(
                                    d -> d.variableName(), d -> d.lambdaImplementation.get()))));

    Map<String, Set<String>> finalMethodVariables =
        parsedSource.findAll(MethodDeclaration.class).stream()
            .collect(
                Collectors.toMap(
                    m ->
                        m.resolve()
                            .getQualifiedSignature()
                            .replaceAll("\\.\\.\\.", "[]")
                            .replaceAll("\\s", ""),
                    m ->
                        m.findAll(VariableDeclarationExpr.class).stream()
                            .filter(v -> v.getModifiers().contains(Modifier.finalModifier()))
                            .map(VariableDeclarationExpr::getVariables)
                            .flatMap(Collection::stream)
                            .map(VariableDeclarator::getNameAsString)
                            .collect(Collectors.toSet())));

    // collect stack frames and their fields
    List<StackSnapshot> stackSnapshots = new LinkedList<>();
    for (StackFrame frame : mainThread.frames()) {
      Method frameMethod = frame.location().method();
      String frameMethodSignature =
          String.format(
              "%s.%s(%s)",
              frameMethod.declaringType().name(),
              frameMethod.name(),
              frameMethod.argumentTypes().stream()
                  .map(Type::name)
                  .collect(Collectors.joining(",")));

      Set<String> finalVariableNames =
          finalMethodVariables.getOrDefault(frameMethodSignature, new HashSet<String>());

      List<ExecutionSnapshot.Field> stackFrameFields = new ArrayList<>();

      Map<String, String> lambdaImplementations =
          Optional.ofNullable(lambdaMethodVariables.get(frameMethodSignature))
              .orElseGet(Collections::emptyMap);

      for (LocalVariable lv : frame.visibleVariables()) {
        boolean isFinal = finalVariableNames.contains(lv.name());
        Optional<String> lvLambdaImplementation =
            Optional.ofNullable(lambdaImplementations.get(lv.name()));

        switch (frame.getValue(lv)) {
          case PrimitiveValue pv ->
              stackFrameFields.add(
                  new ExecutionSnapshot.Field(
                      isFinal,
                      lv.typeName(),
                      lv.name(),
                      TraceValue.Primitive.fromJdiPrimitive(pv)));
          case ObjectReference or when lvLambdaImplementation.isPresent() -> {
            stackFrameFields.add(
                new ExecutionSnapshot.Field(
                    isFinal, lv.typeName(), lv.name(), new TraceValue.Reference(or.uniqueID())));
            heap.put(or.uniqueID(), new TraceValue.Lambda(lvLambdaImplementation.get()));
          }
          case ObjectReference or -> {
            stackFrameFields.add(
                new ExecutionSnapshot.Field(
                    isFinal, lv.typeName(), lv.name(), new TraceValue.Reference(or.uniqueID())));
            heapReferencesToWalk.add(or);
          }
          case null ->
              stackFrameFields.add(
                  new ExecutionSnapshot.Field(
                      isFinal, lv.typeName(), lv.name(), new TraceValue.Null()));
          default -> {}
        }
      }

      Optional<ThisObject> thisObject = Optional.empty();
      if (frame.thisObject() instanceof ObjectReference frameThis) {
        // frameThis is not null, so we're in a nonstatic, nonnative method
        String thisType = frame.location().method().declaringType().name();
        TraceValue.Reference thisReference = new TraceValue.Reference(frameThis.uniqueID());
        thisObject = Optional.of(new ThisObject(thisType, thisReference));
        heapReferencesToWalk.add(frameThis);
      }

      stackSnapshots.addFirst(
          new StackSnapshot(
              frame.location().method().name(),
              frame.location().lineNumber(),
              stackFrameFields,
              thisObject));
    }

    // collect static values that have been loaded
    List<ExecutionSnapshot.Field> statics = new ArrayList<>();
    for (ReferenceType loadedClass : loadedClasses) {
      Optional<ClassOrInterfaceDeclaration> loadedClassDeclaration =
          parsedSource.findFirst(
              ClassOrInterfaceDeclaration.class,
              c ->
                  loadedClass
                      .name()
                      .equals(c.getFullyQualifiedName().orElseGet(c::getNameAsString)));

      for (Field f : loadedClass.allFields()) {
        if (!f.isStatic()) {
          continue;
        }

        Optional<String> lambdaImplementation =
            loadedClassDeclaration
                .flatMap(
                    d ->
                        d.findFirst(
                            VariableDeclarator.class, vd -> vd.getNameAsString().equals(f.name())))
                .filter(vd -> vd.getInitializer().map(Expression::isLambdaExpr).orElse(false))
                .map(vd -> vd.getInitializer().get().asLambdaExpr())
                .flatMap(DebugTraceHelper::tryImplementLambdaSam);

        String fieldName = String.join(".", loadedClass.name(), f.name());
        switch (loadedClass.getValue(f)) {
          case PrimitiveValue pv ->
              statics.add(
                  new ExecutionSnapshot.Field(
                      f.isFinal(),
                      f.typeName(),
                      fieldName,
                      TraceValue.Primitive.fromJdiPrimitive(pv)));
          case ObjectReference or when lambdaImplementation.isPresent() -> {
            heap.put(or.uniqueID(), new TraceValue.Lambda(lambdaImplementation.get()));
            statics.add(
                new ExecutionSnapshot.Field(
                    f.isFinal(), f.typeName(), fieldName, new TraceValue.Reference(or.uniqueID())));
          }
          case ObjectReference or -> {
            statics.add(
                new ExecutionSnapshot.Field(
                    f.isFinal(), f.typeName(), fieldName, new TraceValue.Reference(or.uniqueID())));
            heapReferencesToWalk.add(or);
          }
          case null ->
              statics.add(
                  new ExecutionSnapshot.Field(
                      f.isFinal(), f.typeName(), fieldName, new TraceValue.Null()));
          default -> {}
        }
      }
    }

    // recursively collect heap values reachable from the roots contained in
    // heapReferencesToWalk
    while (!heapReferencesToWalk.isEmpty()) {
      ObjectReference workingObject = heapReferencesToWalk.removeFirst();
      if (heap.containsKey(workingObject.uniqueID())) {
        // don't convert if we've already done so previously, i.e. as in:
        // A ─┐
        //    ├─ C
        // B ─┘
        // both A and B refer to C, parsing it twice (once when we hit A and
        // another time when we hit B), would be a waste.
        continue;
      }

      TraceValue convertedObject =
          TraceValue.fromJdiValue(mainThread, workingObject, Optional.of(heapReferencesToWalk));
      heap.put(workingObject.uniqueID(), convertedObject);
    }

    byte[] vmOutBytes;
    synchronized (vmOut) {
      vmOutBytes = vmOut.toByteArray();
    }

    byte[] vmErrBytes;
    synchronized (vmErr) {
      vmErrBytes = vmErr.toByteArray();
    }

    return new ExecutionSnapshot(stackSnapshots, statics, heap, vmOutBytes, vmErrBytes);
  }
}
