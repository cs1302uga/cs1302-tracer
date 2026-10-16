/**
 * Guest-side harness for executing successive jobs in a persistent JVM.
 *
 * <p>{@link cs1302.tracer.internal.guest.GuestHarness} receives work from the host debugger, loads each submitted
 * program
 * through a
 * disposable class loader, supplies standard input, and signals execution boundaries through debugger hooks.
 * The harness supports process reuse and job cleanup; it does not establish security isolation.</p>
 */
package cs1302.tracer.internal.guest;
