package com.piol.rental.common.tenant;

import com.piol.rental.common.error.ApiException;
import com.piol.rental.user.model.AccountStatus;
import com.piol.rental.user.service.AccessService;
import com.piol.rental.user.service.SpaceAccess;
import com.piol.rental.user.service.UserAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Exécuté après la validation du JWT. Pour chaque requête authentifiée :
 * vérifie que le compte est ACTIVE (un JWT encore valide ne suffit pas : suspension immédiate) ;
 * pour les routes métier, détermine l'espace propriétaire, vérifie l'appartenance et que l'espace est ACTIVE ;
 * publie le résultat dans OwnerContext.
 * Les lectures passent par AccessService (cache Caffeine).
 */
public class AccountContextFilter extends OncePerRequestFilter {

    public static final String OWNER_HEADER = "X-OWNER-Id";

    private static final List<String> NO_SPACE_PREFIXES =
            List.of("/api/v1/auth", "/api/v1/admin", "/api/v1/me", "/api/v1/ping");

    private final AccessService access;
    private final HandlerExceptionResolver resolver;

    public AccountContextFilter(AccessService access, HandlerExceptionResolver resolver) {
        this.access = access;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            chain.doFilter(request, response);
            return;
        }

        OwnerContext.Current context = null;
        try {
            UUID userId = UUID.fromString(jwtAuth.getToken().getSubject());
            checkAccount(userId);
            if (requiresSpace(request.getRequestURI())) {
                context = resolveSpace(userId, request.getHeader(OWNER_HEADER));
            }
        } catch (ApiException ex) {
            // Même format JSON d'erreur que le reste de l'API
            resolver.resolveException(request, response, null, ex);
            return;
        }

        try {
            if (context != null) {
                OwnerContext.set(context);
            }
            chain.doFilter(request, response);
        } finally {
            OwnerContext.clear();
        }
    }
    private void checkAccount(UUID userId) {
        UserAccess user = access.userAccess(userId);
        switch (user.status()) {
            case ACTIVE -> { }
            case SUSPENDED -> throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "Compte suspendu");
            case DELETION_PENDING -> throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_DELETION",
                    "Suppression du compte en cours");
            case DELETED -> throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "Compte introuvable");
        }
    }

    private OwnerContext.Current resolveSpace(UUID userId, String header) {
        UUID ownerId;
        if (header == null || header.isBlank()) {
            List<UUID> spaces = access.spaceIdsOf(userId);
            if (spaces.isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "NO_SPACE", "Aucun espace associé à ce compte");
            }
            if (spaces.size() > 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OWNER_REQUIRED",
                        "Plusieurs espaces : précisez l'en-tête " + OWNER_HEADER);
            }
            ownerId = spaces.get(0);
        } else {
            try {
                ownerId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OWNER_ID", OWNER_HEADER + " invalide");
            }
        }

        // Même réponse que l'espace n'existe pas ou que l'utilisateur n'en soit pas membre
        SpaceAccess space = access.spaceAccess(userId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "SPACE_ACCESS_DENIED",
                        "Vous n'avez pas accès à cet espace"));
        if (space.spaceStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SPACE_SUSPENDED", "Cet espace est suspendu");
        }
        return new OwnerContext.Current(ownerId, space.role());
    }

    private static boolean requiresSpace(String path) {
        if (!path.startsWith("/api/v1/")) {
            return false;
        }
        return NO_SPACE_PREFIXES.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }
}
