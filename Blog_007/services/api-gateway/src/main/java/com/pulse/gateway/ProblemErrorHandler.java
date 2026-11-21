package com.pulse.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns proxy failures into clean Problem Details instead of a generic 500:
 * <ul>
 *   <li>downstream refuses the connection / DNS name unknown → <b>503 Service Unavailable</b> (fast)</li>
 *   <li>downstream too slow (response timeout) → <b>504 Gateway Timeout</b></li>
 * </ul>
 * Order -2 = runs before Spring Boot's default handler (-1).
 */
@Component
@Order(-2)
public class ProblemErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status;
        String title;
        String detail;
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            title = "Service unavailable";
            detail = "The upstream service is not reachable. Please retry shortly.";
        } else if (hasCause(ex, TimeoutException.class)
                || (ex instanceof ResponseStatusException rse && rse.getStatusCode().value() == 504)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            title = "Gateway timeout";
            detail = "The upstream service did not answer in time.";
        } else if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            title = status.getReasonPhrase();
            detail = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            title = "Internal error";
            detail = "Unexpected gateway error.";           // never leak exception messages/stack traces
        }
        if (status.is5xxServerError()) {
            log.warn("{} {} -> {} ({})", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), status.value(), ex.toString());
        }
        return Problems.write(exchange, status, title, detail);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
