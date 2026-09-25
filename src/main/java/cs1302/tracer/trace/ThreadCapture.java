package cs1302.tracer.trace;

import com.sun.jdi.ThreadGroupReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.EventRequest;
import cs1302.tracer.execution.TraceSession;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-job thread discovery and event metadata for a dedicated guest JVM. */
public final class ThreadCapture {
    private final Set<String> classes = new HashSet<>();
    private final Set<Long> observed = new HashSet<>();
    private ThreadGroupReference applicationGroup;
    private Long dyingThread;
    private String event = "step_line";

    /**
     * Installs lifecycle events before the initial VM suspension is resumed.
     * @param vm Dedicated guest VM.
     * @param names Submitted class names.
     */
    public void install(VirtualMachine vm, Collection<String> names) {
        classes.addAll(names);
        var start = vm.eventRequestManager().createThreadStartRequest();
        start.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        start.enable();
        var death = vm.eventRequestManager().createThreadDeathRequest();
        death.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        death.enable();
    } // install

    /**
     * Updates event context and returns a lifecycle thread requiring a snapshot.
     * @param value Current debugger event.
     * @return Application lifecycle thread, or null.
     */
    public ThreadReference observe(Event value) {
        dyingThread = null;
        event = "step_line";
        if (value instanceof VMStartEvent start) {
            applicationGroup = start.thread().threadGroup();
        } else if (value instanceof ThreadStartEvent start) {
            rejectVirtual(start.thread());
            if (belongs(start.thread())) {
                event = "thread_start";
                return start.thread();
            } // if
        } else if (value instanceof ThreadDeathEvent death) {
            if (belongs(death.thread())) {
                event = "thread_death";
                dyingThread = death.thread().uniqueID();
                observed.remove(dyingThread);
                return death.thread();
            } // if
        } else {
            if (value instanceof ExceptionEvent) {
                event = "exception";
            } else {
                if (value instanceof MethodExitEvent exit && exit.method().name().equals("main")) {
                    event = "main_exit";
                } // if
            } // if
        } // if
        return null;
    } // observe

    /**
     * Discovers application threads while all guest threads are suspended.
     * @param trigger Event thread.
     * @return Live application threads sorted by stable identity.
     */
    public List<ThreadReference> threads(ThreadReference trigger) {
        rejectVirtual(trigger);
        if (!isDying(trigger)) {
            observed.add(trigger.uniqueID());
        } // if
        return trigger.virtualMachine().allThreads().stream()
                .filter(t -> !isDying(t)).filter(this::belongs)
                .sorted(Comparator.comparingLong(ThreadReference::uniqueID)).toList();
    } // threads

    /**
     * Identifies threads in the entry thread's group, descendants, or observed user code.
     * @param thread Guest thread.
     * @return True for an application thread.
     */
    private boolean belongs(ThreadReference thread) {
        if (observed.contains(thread.uniqueID())) {
            return true;
        } // if
        for (var group = thread.threadGroup(); group != null; group = group.parent()) {
            if (group.equals(applicationGroup)) {
                return true;
            } // if
        } // for
        return false;
    } // belongs

    /**
     * Rejects virtual threads explicitly, including threads without submitted source frames.
     * @param thread Started or executing thread.
     */
    private void rejectVirtual(ThreadReference thread) {
        if (thread.isVirtual()) {
            TraceSession.current().stop("unsupported_virtual_thread");
            throw new TraceSession.Stopped(TraceSession.current().stopReason());
        } // if
    } // rejectVirtual

    /**
     * Tests whether a stack frame belongs to submitted code.
     * @param name Declaring class name.
     * @return True for submitted classes.
     */
    public boolean applicationClass(String name) {
        return classes.contains(name);
    } // applicationClass

    /**
     * Tests whether the current event reports this thread's termination.
     * @param thread Guest thread.
     * @return True for the terminating thread.
     */
    public boolean isDying(ThreadReference thread) {
        return dyingThread != null && dyingThread.longValue() == thread.uniqueID();
    } // isDying

    /**
     * Returns the current event kind.
     * @return Trace event kind.
     */
    public String event() {
        return event;
    } // event

    /**
     * Maps the JDI status without confusing debugger suspension with application blocking.
     * @param thread Guest thread.
     * @return Stable status label.
     */
    public static String state(ThreadReference thread) {
        return switch (thread.status()) {
        case ThreadReference.THREAD_STATUS_ZOMBIE -> "TERMINATED";
        case ThreadReference.THREAD_STATUS_NOT_STARTED -> "NEW";
        case ThreadReference.THREAD_STATUS_RUNNING -> "RUNNABLE";
        case ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING";
        case ThreadReference.THREAD_STATUS_MONITOR -> "BLOCKED";
        case ThreadReference.THREAD_STATUS_WAIT -> "WAITING";
        default -> "UNKNOWN";
        }; // switch
    } // state
} // ThreadCapture
