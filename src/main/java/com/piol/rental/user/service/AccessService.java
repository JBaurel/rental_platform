package com.piol.rental.user.service;

import com.piol.rental.user.model.AccountStatus;
import com.piol.rental.user.model.PlatformRole;
import com.piol.rental.user.repo.AppUserRepository;
import com.piol.rental.user.repo.OwnerMembershipRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lectures d'accès appelées à CHAQUE requête authentifiée, donc mises en cache
 * (Caffeine, expiration courte : voir spring.cache.caffeine.spec).
 * Délai maximal avant prise en compte d'une suspension ou d'un retrait de droits
 * = durée du cache, sauf si les méthodes evict* sont appelées au moment du changement.
 */
@Service
public class AccessService {

    private final AppUserRepository users;
    private final OwnerMembershipRepository memberships;

    public AccessService(AppUserRepository users, OwnerMembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Cacheable(cacheNames = "userAccess", key = "#userId")
    @Transactional(readOnly = true)
    public UserAccess userAccess(UUID userId) {
        return users.findById(userId)
                .map(u -> new UserAccess(u.getStatus(), u.getPlatformRole()))
                .orElse(new UserAccess(AccountStatus.DELETED, PlatformRole.USER));
    }

    @Cacheable(cacheNames = "userSpaces", key = "#userId")
    @Transactional(readOnly = true)
    public List<UUID> spaceIdsOf(UUID userId) {
        return memberships.findOwnerIdsByUserId(userId);
    }

    @Cacheable(cacheNames = "spaceAccess", key = "#userId.toString() + ':' + #ownerId.toString()")
    @Transactional(readOnly = true)
    public Optional<SpaceAccess> spaceAccess(UUID userId, UUID ownerId) {
        return memberships.findAccess(userId, ownerId);
    }

    // --- Invalidation : à appeler lors d'une suspension, d'un retrait de droits, etc. ---

    @CacheEvict(cacheNames = "userAccess", key = "#userId")
    public void evictUser(UUID userId) {
    }

    @CacheEvict(cacheNames = "userSpaces", key = "#userId")
    public void evictUserSpaces(UUID userId) {
    }

    @CacheEvict(cacheNames = "spaceAccess", allEntries = true)
    public void evictAllSpaceAccess() {
    }
}

