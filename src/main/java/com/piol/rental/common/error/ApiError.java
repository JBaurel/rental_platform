package com.piol.rental.common.error;

//import com.piol.rental.common.CorrelationIdFilter;
import org.slf4j.MDC;

import com.piol.rental.common.web.CorrelationIdFilter;

import java.time.Instant;
import java.util.List;

public record ApiError (
        Instant timestamp,
        int status,
        String code,            
        String message,         
        String path,
        String correlationId,   
        List<FieldViolation> violations) {
 
    public record FieldViolation(String field, String message) {}
 
    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path,
                MDC.get(CorrelationIdFilter.MDC_KEY), List.of());
    }
 
    public ApiError withViolations(List<FieldViolation> violations) {
        return new ApiError(timestamp, status, code, message, path, correlationId, violations);
    }
}