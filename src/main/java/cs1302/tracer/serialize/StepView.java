package cs1302.tracer.serialize;

import java.util.AbstractList;
import java.util.Objects;
import java.util.function.IntFunction;

/** Read-only step list that materializes one serializer model at a time. */
public final class StepView<T> extends AbstractList<T> {
    private final int count;
    private final IntFunction<T> factory;

    /**
     * Constructs a view without retaining generated step models.
     * @param count Number of steps.
     * @param factory Indexed step conversion.
     */
    public StepView(int count, IntFunction<T> factory) {
        this.count = count;
        this.factory = factory;
    } // StepView

    @Override
    public T get(int index) {
        return factory.apply(Objects.checkIndex(index, count));
    } // get

    @Override
    public int size() {
        return count;
    } // size
} // StepView
