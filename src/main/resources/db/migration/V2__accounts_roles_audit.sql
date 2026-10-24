-- =====================================================================
-- V2 : comptes (e-mail OU téléphone), rôles plateforme, statuts,
--      codes OTP et journal d'audit.
--
-- Rôles plateforme : USER (propriétaire / gérant) et ADMIN.
-- Le rôle DANS un espace propriétaire reste porté par owner_membership
-- (OWNER / MANAGER) : un utilisateur peut être OWNER d'un espace et
-- MANAGER d'un autre, donc ce rôle n'est jamais mis dans le JWT.
--
-- À prévoir (Phase 3) : l'abonnement est rattaché à un PAYEUR (utilisateur,
-- ex. une agence) et non à un espace : une agence paie pour N espaces.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. app_user : identifiants multiples, rôle plateforme, statut
-- ---------------------------------------------------------------------

-- Un compte peut n'avoir qu'un téléphone et se connecter par OTP (sans mot de passe)
ALTER TABLE app_user ALTER COLUMN email         DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE app_user
    ADD COLUMN email_verified_at     TIMESTAMPTZ,
    ADD COLUMN phone_verified_at     TIMESTAMPTZ,
    ADD COLUMN platform_role         VARCHAR(10) NOT NULL DEFAULT 'USER'
        CHECK (platform_role IN ('USER', 'ADMIN')),
    ADD COLUMN status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED')),
    ADD COLUMN suspended_at          TIMESTAMPTZ,
    ADD COLUMN suspension_reason     VARCHAR(500),
    ADD COLUMN deletion_requested_at TIMESTAMPTZ;

-- Remplacé par status
ALTER TABLE app_user DROP COLUMN enabled;

-- Au moins un identifiant de connexion
ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_identifier CHECK (email IS NOT NULL OR phone IS NOT NULL);

-- Téléphone au format international E.164 (ex. +221771234567)
ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_phone_e164 CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$');

CREATE UNIQUE INDEX ux_app_user_phone ON app_user (phone) WHERE phone IS NOT NULL;
-- (ux_app_user_email sur lower(email) existe depuis V1 ; plusieurs NULL sont autorisés)

-- ---------------------------------------------------------------------
-- 2. owner (espace) : statut
-- ---------------------------------------------------------------------

ALTER TABLE owner
    ADD COLUMN status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED')),
    ADD COLUMN suspended_at          TIMESTAMPTZ,
    ADD COLUMN deletion_requested_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 3. Codes à usage unique (OTP) : connexion et vérifications
--    On stocke uniquement le hash du code.
-- ---------------------------------------------------------------------

CREATE TABLE otp_challenge (
                               id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                               channel     VARCHAR(10)  NOT NULL CHECK (channel IN ('SMS', 'WHATSAPP', 'EMAIL')),
                               target      VARCHAR(254) NOT NULL,              -- numéro E.164 ou adresse e-mail
                               purpose     VARCHAR(20)  NOT NULL
                                   CHECK (purpose IN ('LOGIN', 'VERIFY_PHONE', 'VERIFY_EMAIL', 'RESET')),
                               code_hash   VARCHAR(128) NOT NULL,
                               expires_at  TIMESTAMPTZ  NOT NULL,
                               attempts    SMALLINT     NOT NULL DEFAULT 0,    -- tentatives de saisie (limitées côté code)
                               consumed_at TIMESTAMPTZ,
                               request_ip  VARCHAR(45),
                               created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Sert aussi à limiter le nombre de demandes par cible et par période
CREATE INDEX ix_otp_target_created ON otp_challenge (target, created_at DESC);

-- ---------------------------------------------------------------------
-- 4. Journal d'audit (actions sensibles : connexions, suspensions, accès support...)
--    Pas de clé étrangère : le journal doit survivre à la suppression d'un compte.
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
                           id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                           occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                           actor_user_id  UUID,
                           actor_role     VARCHAR(20),
                           owner_id       UUID,
                           action         VARCHAR(60)  NOT NULL,
                           target_type    VARCHAR(40),
                           target_id      VARCHAR(64),
                           metadata       JSONB,
                           ip             VARCHAR(45),
                           correlation_id VARCHAR(64)
);
CREATE INDEX ix_audit_owner_time  ON audit_log (owner_id, occurred_at DESC);
CREATE INDEX ix_audit_actor_time  ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_action_time ON audit_log (action, occurred_at DESC);
