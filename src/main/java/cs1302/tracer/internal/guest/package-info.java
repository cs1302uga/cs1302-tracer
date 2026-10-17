/**
 * Guest-side execution support and prepared binary dependency loading.
 *
 * <p>{@link cs1302.tracer.internal.guest.GuestHarness} receives work from the host debugger, loads each submitted
 * program through a disposable class loader, supplies standard input, and signals execution boundaries through
 * debugger hooks. When a job has binary dependencies, {@link cs1302.tracer.internal.guest.GuestClassPath}
 * transports the already prepared search order to {@link cs1302.tracer.internal.guest.GuestClassLoader}.
 * Lookup retains platform precedence, JDK signature verification, package sealing, multi-release selection,
 * and resource/service discovery without expanding manifest paths again. This keeps copied dependencies
 * independent of their original locations.</p>
 *
 * <p>Dedicated guests with dependencies use {@link cs1302.tracer.internal.guest.GuestSystemClassLoader}
 * while retaining the JVM's native main invocation and process lifetime. Persistent guests retain their
 * existing harness lifecycle and close the application loader after each job. These helpers require only
 * JDK runtime types; guest loading and reusable-process cleanup do not establish security isolation.</p>
 */
package cs1302.tracer.internal.guest;
