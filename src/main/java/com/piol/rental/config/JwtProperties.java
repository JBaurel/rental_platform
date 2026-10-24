package com.piol.rental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("piol-rental") String issuer,
        @DefaultValue("10m")Duration accessTtl,
        @DefaultValue("3d") Duration refreshTtl
        ) { }
