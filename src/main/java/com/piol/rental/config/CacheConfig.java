package com.piol.rental.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Caches Caffeine (userAccess, userSpaces, spaceAccess) */

@Configuration
@EnableCaching
public class CacheConfig {
}
