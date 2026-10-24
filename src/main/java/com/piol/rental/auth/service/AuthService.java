package com.piol.rental.auth.service;

import org.springframework.stereotype.Service;

import com.piol.rental.auth.dto.LoginRequest;
import com.piol.rental.auth.dto.RegisterRequest;
import com.piol.rental.auth.dto.TokenResponse;
import com.piol.rental.auth.model.RefreshToken;
import com.piol.rental.auth.repo.RefreshTokenRepository;
import com.piol.rental.config.JwtProperties;
import com.piol.rental.common.error.ApiException;
import com.piol.rental.user.model.AccountStatus;
import com.piol.rental.user.model.AppUser;
import com.piol.rental.user.model.MembershipRole;
import com.piol.rental.user.model.Owner;
import com.piol.rental.user.model.OwnerMembership;
import com.piol.rental.user.model.OwnerMembershipId;
import com.piol.rental.user.repo.AppUserRepository;
import com.piol.rental.user.repo.OwnerMembershipRepository;
import com.piol.rental.user.repo.OwnerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final OwnerRepository owners;
    private final OwnerMembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties props;

    private final SecureRandom random = new SecureRandom();
    // Hash factice : on fait toujours un calcul BCrypt, même si le compte n'existe pas
    // (évite de révéler par le temps de réponse qu'un identifiant existe).
    private final String dummyHash;

    public AuthService(AppUserRepository users,
                       OwnerRepository owners,
                       OwnerMembershipRepository memberships,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties props) {
        this.users = users;
        this.owners = owners;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * Inscription ouverte : crée l'utilisateur (rôle USER), son espace propriétaire
     * et le rattachement OWNER. Aucun rôle n'est jamais lu depuis la requête.
     * TODO étape 3 : vérification de l'e-mail avant de considérer le compte comme vérifié.
     */
    @Transactional
    public TokenResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String phone = (req.phone() == null || req.phone().isBlank()) ? null : req.phone().trim();

        if (users.existsByEmail(email) || (phone != null && users.existsByPhone(phone))) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS",
                    "Un compte existe déjà avec cet e-mail ou ce téléphone");
        }

        Owner owner = new Owner();
        owner.setName(req.fullName().trim());
        owner.setEmail(email);
        owner.setPhone(phone);
        owner = owners.save(owner);

        AppUser user = new AppUser();
        user.setFullName(req.fullName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user = users.save(user);

        memberships.save(new OwnerMembership(
                new OwnerMembershipId(user.getId(), owner.getId()), MembershipRole.OWNER));

        return issueTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        String identifier = req.identifier().trim();
        Optional<AppUser> found = identifier.contains("@")
                ? users.findByEmail(identifier.toLowerCase(Locale.ROOT))
                : users.findByPhone(identifier);

        AppUser user = found.orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        boolean passwordOk = passwordEncoder.matches(req.password(), hasPassword ? user.getPasswordHash() : dummyHash);

        if (user == null || !hasPassword || !passwordOk) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Identifiant ou mot de passe incorrect");
        }
        assertCanLogin(user);
        return issueTokens(user);
    }

    /**
     * Rotation : chaque refresh token est à usage unique. Si un token déjà utilisé est présenté
     * à nouveau (vol probable), tous les tokens de l'utilisateur sont révoqués.
     * noRollbackFor : la révocation doit être conservée même si on lève une exception ensuite.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawToken) {
        RefreshToken token = refreshTokens.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Jeton de renouvellement invalide"));

        Instant now = Instant.now();
        if (token.getRevokedAt() != null) {
            refreshTokens.revokeAllActiveForUser(token.getUserId(), now);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "Jeton déjà utilisé : reconnectez-vous");
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED",
                    "Session expirée : reconnectez-vous");
        }

        AppUser user = users.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Jeton de renouvellement invalide"));
        if (user.getStatus() != AccountStatus.ACTIVE) {
            refreshTokens.revokeAllActiveForUser(user.getId(), now);
            assertCanLogin(user);
        }

        token.setRevokedAt(now);
        return issueTokens(user);
    }

    /** Idempotent : un token inconnu ou déjà révoqué ne provoque pas d'erreur. */
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(sha256Hex(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
            }
        });
    }

    // ------------------------------------------------------------------

    private void assertCanLogin(AppUser user) {
        switch (user.getStatus()) {
            case ACTIVE -> { }
            case SUSPENDED -> throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "Compte suspendu");
            case DELETION_PENDING -> throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_DELETION",
                    "Suppression du compte en cours");
            case DELETED -> throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Identifiant ou mot de passe incorrect");
        }
    }

    private TokenResponse issueTokens(AppUser user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256Hex(rawRefresh));
        token.setExpiresAt(Instant.now().plus(props.refreshTtl()));
        refreshTokens.save(token);

        return new TokenResponse(jwtService.issueAccessToken(user), rawRefresh, "Bearer",
                jwtService.accessTtlSeconds());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

