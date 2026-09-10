package ar.edu.utn.frc.tup.p4.apigateway.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared record of where a request went, in actual order. */
public final class FilterSequence {

    private static final List<String> STEPS = Collections.synchronizedList(new ArrayList<>());

    public static void register(String step) { STEPS.add(step); }
    public static List<String> steps() { return List.copyOf(STEPS); }
    public static void clear() { STEPS.clear(); }

    private FilterSequence() { }
}
