package com.piol.rental.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
 
/** Test de bout en bout : n'accède volontairement PAS à la base de données. */
@RestController
@RequestMapping("/api/v1")
public class PingController {
 
    private static final Logger log = LoggerFactory.getLogger(PingController.class);
 
    private final String applicationName;
 
    public PingController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }
 
    public record PingResponse(String status, String application, Instant timestamp, String correlationId) {}
 
    @GetMapping("/ping")
    public PingResponse ping() {
        log.info("Ping reçu");
        return new PingResponse("UP", applicationName, Instant.now(), MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
 
