package com.piol.rental.user.repo;

import com.piol.rental.user.model.AppUser;
import com.piol.rental.user.model.PlatformRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
    Optional<AppUser> findByPhone(String phone);

    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByPlatformRole(PlatformRole role);

}
