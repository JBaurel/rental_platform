package com.piol.rental.auth.service;
import com.piol.rental.user.model.AppUser;
import com.piol.rental.user.model.PlatformRole;
import com.piol.rental.user.repo.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Crée le premier compte ADMIN au démarrage, depuis APP_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD,
 * uniquement s'il n'existe encore aucun admin. L'inscription publique ne crée jamais d'admin.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminBootstrap(AppUserRepository users,
                          PasswordEncoder passwordEncoder,
                          @Value("${app.bootstrap-admin.email:}") String email,
                          @Value("${app.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (users.existsByPlatformRole(PlatformRole.ADMIN)) {
            return;
        }
        if (password.length() < 12) {
            log.error("Admin initial non créé : APP_BOOTSTRAP_ADMIN_PASSWORD doit contenir au moins 12 caractères.");
            return;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalizedEmail)) {
            log.error("Admin initial non créé : un compte utilise déjà l'e-mail {}.", normalizedEmail);
            return;
        }
        AppUser admin = new AppUser();
        admin.setFullName("Admin");
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setPlatformRole(PlatformRole.ADMIN);
        admin.setEmailVerifiedAt(Instant.now());
        users.save(admin);
        log.info("Compte administrateur initial créé pour {}.", normalizedEmail);
    }
}
