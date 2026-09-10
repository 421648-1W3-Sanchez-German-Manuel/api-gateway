package ar.edu.utn.frc.tup.p4.apigateway.ratelimit;

public interface TokenBucket {
    /** true when there is budget left; false when it ran out. */
    boolean consume(String key, int capacidad, int recargaPorMinuto);
    java.time.Duration suggestedWait(String key);
}
