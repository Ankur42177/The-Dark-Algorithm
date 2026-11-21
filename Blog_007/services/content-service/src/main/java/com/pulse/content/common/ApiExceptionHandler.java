package com.pulse.content.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Turns exceptions into consistent JSON errors following RFC 9457 "Problem Details":
 * <pre>
 * {
 *   "type": "https://pulse.dev/problems/post-not-found",
 *   "title": "Post not found",
 *   "status": 404,
 *   "detail": "No published post with slug 'abc'",
 *   "instance": "/api/v1/posts/abc"
 * }
 * </pre>
 *
 * <p>{@code @RestControllerAdvice} = "applies to all controllers". Extending
 * {@link ResponseEntityExceptionHandler} gives us Problem Details for Spring's built-in errors too
 * (bad parameter type, validation failures, unknown URL, wrong HTTP method...).
 *
 * <p>Rule of thumb: 4xx = the client did something wrong (tell them what); 5xx = we did
 * (log it, and never leak stack traces or SQL to the client).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_BASE = "https://pulse.dev/problems/";

    @ExceptionHandler(PostNotFoundException.class)
    public ProblemDetail handleNotFound(PostNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create(PROBLEM_BASE + "post-not-found"));
        problem.setTitle("Post not found");
        return problem;
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleBadCursor(InvalidCursorException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(PROBLEM_BASE + "invalid-cursor"));
        problem.setTitle("Invalid cursor");
        return problem;
    }
}
