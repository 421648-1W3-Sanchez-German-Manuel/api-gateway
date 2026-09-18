package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.filters.CorrelationIdFilter;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * The missing bridge between Reactor's context and the MDC.
 *
 * `CorrelationIdFilter` publishes the request ids in the REACTOR CONTEXT,
 * which is the right thing: in WebFlux a request hops between threads and a
 * ThreadLocal is lost. But `logback-spring.xml` prints them with `%X{...}`,
 * and `%X` reads the MDC, which IS a ThreadLocal. Both ends were fine and
 * there was nothing in the middle: every log line came out with the field
 * empty.
 *
 * The symptom did not look like a bug. The ids were generated, travelled to
 * the destination by headers and came back in the response; the only thing
 * missing was exactly where it is needed, which is the log. It looks like
 * this:
 *
 *   [api-gateway,,,] LoggingFilter - GET /api/users/me -> 200 (9 ms)
 *
 * Three empty fields between the commas. Criterion 9 of the DoD: following a
 * request end to end across Gateway and microservice logs with a single id.
 * With the field empty nothing can be followed.
 *
 * The three ids all come from the same `CorrelationIdFilter`: `requestId`
 * (generated or incoming) and `traceId`/`spanId` derived from the W3C
 * `traceparent` — the same values that travel downstream and that
 * microservices like users-service put in THEIR logs. That the Gateway logs
 * the same is what closes the trail.
 *
 * The fix: register one {@link ThreadLocalAccessor} per key, and Reactor
 * restores the MDC around each signal.
 *
 * Reactor's hook was ALREADY set: `spring.reactor.context-propagation: auto`
 * in the application.yml, with a comment explaining this same problem. The
 * only thing missing was the accessor, because the automatic propagation only
 * moves the keys that someone registered. Both ends were right and the middle
 * one did not exist.
 */
@Configuration
public class CorrelationMdcConfig {

    @PostConstruct
    void registerBridge() {
        // Idempotent: registering the same key twice replaces, does not
        // duplicate. It matters because Spring's context is cached across test
        // classes.
        //
        // No need to call Hooks.enableAutomaticContextPropagation(): Boot does
        // it for `spring.reactor.context-propagation: auto`.
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                new MdcKeyAccessor(CorrelationIdFilter.CTX_REQUEST_ID));
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                new MdcKeyAccessor(CorrelationIdFilter.CTX_TRACE_ID));
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                new MdcKeyAccessor(CorrelationIdFilter.CTX_SPAN_ID));
    }

    /**
     * The accessor's key must be EXACTLY the same as the one of Reactor's
     * context, or nothing is restored and the symptom is identical to not
     * having the bridge. That is why it comes from the constant and not from a
     * literal. One accessor per key: `requestId`, `traceId` and `spanId`, all
     * the same.
     */
    static final class MdcKeyAccessor implements ThreadLocalAccessor<String> {

        private final String key;

        MdcKeyAccessor(String key) {
            this.key = key;
        }

        @Override
        public Object key() {
            return key;
        }

        @Override
        public String getValue() {
            return MDC.get(key);
        }

        @Override
        public void setValue(String value) {
            MDC.put(key, value);
        }

        /** Leaving the scope: clean, or the id leaks into the next request. */
        @Override
        public void setValue() {
            MDC.remove(key);
        }
    }
}
