-- =====================================================================
-- V1 : schéma initial de la plateforme de gestion locative
--
-- Conventions
--  * Toute table métier porte owner_id (clé de tenant / future clé de sharding).
--  * Les tables enfants référencent leur parent via une clé étrangère
--    COMPOSITE (owner_id, parent_id) : impossible de lier des données de
--    deux propriétaires différents, même par erreur applicative.
--  * Montants : NUMERIC(19,4) + code devise ISO 4217 (CHAR(3)), jamais de float.
--  * Dates techniques en TIMESTAMPTZ (UTC). version = verrouillage optimiste JPA.
--  * Tables NON métier (sans owner_id) : app_user, refresh_token,
--    deduction_reason, exchange_rate. payment_event.owner_id est nullable
--    (le propriétaire n'est connu qu'après traitement du webhook).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS archive;

-- ---------------------------------------------------------------------
-- 1. Identité et accès
-- ---------------------------------------------------------------------

-- Le propriétaire est le tenant : son id EST l'owner_id des autres tables.
CREATE TABLE owner (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(200) NOT NULL,
    email            VARCHAR(254),
    phone            VARCHAR(30),
    default_currency CHAR(3)      NOT NULL DEFAULT 'XOF',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);

-- Personne qui se connecte (distincte du tenant : un gestionnaire délégué
-- pourra agir pour le compte d'un propriétaire).
CREATE TABLE app_user (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    phone         VARCHAR(30),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));

CREATE TABLE owner_membership (
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    owner_id   UUID        NOT NULL REFERENCES owner (id)    ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'MANAGER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, owner_id)
);
CREATE INDEX ix_owner_membership_owner ON owner_membership (owner_id);

-- Refresh tokens (on stocke uniquement le hash du token, avec rotation).
CREATE TABLE refresh_token (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- ---------------------------------------------------------------------
-- 2. Parc immobilier : Propriétaire > Immeuble > Unité
-- ---------------------------------------------------------------------

CREATE TABLE building (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID         NOT NULL REFERENCES owner (id),
    name       VARCHAR(200) NOT NULL,
    address    VARCHAR(300),
    city       VARCHAR(100),
    country    CHAR(2),
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT', 'ACTIVE')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version    BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id)
);

CREATE TABLE unit (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID          NOT NULL,
    building_id  UUID          NOT NULL,
    label        VARCHAR(50)   NOT NULL,
    floor        SMALLINT,
    unit_type    VARCHAR(30),
    default_rent NUMERIC(19,4) CHECK (default_rent >= 0),
    currency     CHAR(3),
    status       VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT', 'ACTIVE')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version      BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    UNIQUE (building_id, label),
    FOREIGN KEY (owner_id, building_id) REFERENCES building (owner_id, id)
);
-- NB : le statut Vert/Orange/Rouge/Gris n'est PAS stocké, il est calculé
-- à partir des baux, échéances et paiements.

-- ---------------------------------------------------------------------
-- 3. Locataires et baux
-- ---------------------------------------------------------------------

CREATE TABLE tenant (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id       UUID         NOT NULL REFERENCES owner (id),
    tenant_code    VARCHAR(20)  NOT NULL,           -- code unique d'identification (paiements)
    full_name      VARCHAR(200) NOT NULL,
    phone          VARCHAR(30),
    whatsapp_phone VARCHAR(30),
    email          VARCHAR(254),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, tenant_code)
);

CREATE TABLE lease (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID          NOT NULL,
    unit_id           UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    start_date        DATE          NOT NULL,
    end_date          DATE,
    rent_amount       NUMERIC(19,4) NOT NULL CHECK (rent_amount >= 0),
    charges_amount    NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (charges_amount >= 0),
    deposit_amount    NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (deposit_amount >= 0),
    currency          CHAR(3)       NOT NULL,
    due_day           SMALLINT      NOT NULL DEFAULT 5 CHECK (due_day BETWEEN 1 AND 28),
    status            VARCHAR(10)   NOT NULL DEFAULT 'DRAFT'
                                    CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    contract_file_key VARCHAR(500),                 -- clé dans FileStorageService
    closed_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    CHECK (end_date IS NULL OR end_date >= start_date),
    FOREIGN KEY (owner_id, unit_id)   REFERENCES unit (owner_id, id),
    FOREIGN KEY (owner_id, tenant_id) REFERENCES tenant (owner_id, id)
);
-- Un seul bail actif par unité
CREATE UNIQUE INDEX ux_lease_one_active_per_unit ON lease (unit_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_lease_owner_status ON lease (owner_id, status);
CREATE INDEX ix_lease_owner_unit   ON lease (owner_id, unit_id);
CREATE INDEX ix_lease_owner_tenant ON lease (owner_id, tenant_id);

-- ---------------------------------------------------------------------
-- 4. Échéances, paiements, reçus
-- ---------------------------------------------------------------------

CREATE TABLE rent_due (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id       UUID          NOT NULL,
    lease_id       UUID          NOT NULL,
    period_start   DATE          NOT NULL,          -- 1er jour du mois concerné
    due_date       DATE          NOT NULL,
    rent_amount    NUMERIC(19,4) NOT NULL CHECK (rent_amount >= 0),
    charges_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (charges_amount >= 0),
    amount_due     NUMERIC(19,4) NOT NULL CHECK (amount_due >= 0),
    currency       CHAR(3)       NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    UNIQUE (lease_id, period_start),
    FOREIGN KEY (owner_id, lease_id) REFERENCES lease (owner_id, id)
);
CREATE INDEX ix_rent_due_owner_due_date ON rent_due (owner_id, due_date);

-- Paiements fractionnés : plusieurs paiements peuvent viser la même échéance.
CREATE TABLE payment (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID          NOT NULL,
    lease_id           UUID          NOT NULL,
    rent_due_id        UUID,                         -- NULL = acompte non affecté
    method             VARCHAR(20)   NOT NULL CHECK (method IN ('MOBILE_MONEY', 'CASH')),
    amount             NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency           CHAR(3)       NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'PENDING'
                                     CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    paid_at            TIMESTAMPTZ,
    provider           VARCHAR(50),                  -- agrégateur Mobile Money
    provider_reference VARCHAR(100),                 -- référence de transaction
    note               TEXT,
    created_by         UUID          REFERENCES app_user (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    CHECK (method = 'CASH' OR (provider IS NOT NULL AND provider_reference IS NOT NULL)),
    FOREIGN KEY (owner_id, lease_id)    REFERENCES lease (owner_id, id),
    FOREIGN KEY (owner_id, rent_due_id) REFERENCES rent_due (owner_id, id)
);
-- Idempotence : une même transaction Mobile Money ne peut être enregistrée qu'une fois
CREATE UNIQUE INDEX ux_payment_provider_ref
    ON payment (provider, provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX ix_payment_owner_lease ON payment (owner_id, lease_id);
CREATE INDEX ix_payment_owner_due   ON payment (owner_id, rent_due_id);
CREATE INDEX ix_payment_owner_paid  ON payment (owner_id, paid_at);

-- Boîte de réception des webhooks (journal brut + déduplication)
CREATE TABLE payment_event (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID         REFERENCES owner (id),   -- renseigné après résolution
    provider          VARCHAR(50)  NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'RECEIVED'
                                   CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    error             TEXT,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    UNIQUE (provider, provider_event_id)
);

CREATE TABLE receipt (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         UUID         NOT NULL,
    payment_id       UUID         NOT NULL,
    receipt_number   VARCHAR(40)  NOT NULL,
    file_key         VARCHAR(500),                  -- PDF dans FileStorageService
    share_token_hash VARCHAR(128) UNIQUE,           -- lien sécurisé (accès public via hash du token)
    share_expires_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, receipt_number),
    UNIQUE (owner_id, payment_id),
    FOREIGN KEY (owner_id, payment_id) REFERENCES payment (owner_id, id)
);

-- ---------------------------------------------------------------------
-- 5. Fin de bail : solde de tout compte
-- ---------------------------------------------------------------------

-- Liste fixe de motifs de retenue (référentiel global, pas de owner_id)
CREATE TABLE deduction_reason (
    code       VARCHAR(50)  PRIMARY KEY,
    label_fr   VARCHAR(200) NOT NULL,
    sort_order SMALLINT     NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO deduction_reason (code, label_fr, sort_order) VALUES
    ('UNPAID_RENT',      'Loyers impayés',                              1),
    ('UNPAID_UTILITIES', 'Charges et factures impayées (eau, électricité)', 2),
    ('DAMAGE',           'Dégradations constatées',                     3),
    ('REPAINTING',       'Remise en peinture',                          4),
    ('CLEANING',         'Frais de nettoyage',                          5),
    ('LOST_KEYS',        'Clés perdues ou non restituées',              6),
    ('REPAIRS',          'Réparations',                                 7);

-- Solde = caution - loyers impayés - retenues. Négatif = le locataire doit encore de l'argent.
CREATE TABLE deposit_settlement (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID          NOT NULL,
    lease_id           UUID          NOT NULL,
    currency           CHAR(3)       NOT NULL,
    deposit_amount     NUMERIC(19,4) NOT NULL CHECK (deposit_amount >= 0),
    unpaid_rent_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (unpaid_rent_amount >= 0),
    deductions_amount  NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (deductions_amount >= 0),
    balance_amount     NUMERIC(19,4) NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VALIDATED')),
    validated_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (owner_id, id),
    UNIQUE (lease_id),
    FOREIGN KEY (owner_id, lease_id) REFERENCES lease (owner_id, id)
);

CREATE TABLE deposit_deduction (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID          NOT NULL,
    settlement_id UUID          NOT NULL,
    reason_code   VARCHAR(50)   NOT NULL REFERENCES deduction_reason (code),
    description   VARCHAR(500),
    amount        NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id, settlement_id) REFERENCES deposit_settlement (owner_id, id)
);
CREATE INDEX ix_deposit_deduction_settlement ON deposit_deduction (owner_id, settlement_id);

-- ---------------------------------------------------------------------
-- 6. Devises (référentiel global alimenté par un job quotidien)
-- ---------------------------------------------------------------------

CREATE TABLE exchange_rate (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3)        NOT NULL,
    quote_currency CHAR(3)        NOT NULL,
    rate           NUMERIC(20,10) NOT NULL CHECK (rate > 0),
    rate_date      DATE           NOT NULL,
    source         VARCHAR(50),
    fetched_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (base_currency, quote_currency, rate_date)
);

-- ---------------------------------------------------------------------
-- 7. Notifications (SMS / WhatsApp / Email) traitées par les workers
-- ---------------------------------------------------------------------

CREATE TABLE notification (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    lease_id            UUID,
    rent_due_id         UUID,
    payment_id          UUID,
    channel             VARCHAR(10)  NOT NULL CHECK (channel IN ('SMS', 'WHATSAPP', 'EMAIL')),
    kind                VARCHAR(20)  NOT NULL
                                     CHECK (kind IN ('REMINDER_T14', 'REMINDER_T7', 'REMINDER_T2', 'REMINDER_T1', 'RECEIPT')),
    status              VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                                     CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED')),
    attempts            INT          NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    provider_message_id VARCHAR(100),               -- pour le webhook d'accusé de livraison
    last_error          TEXT,
    sent_at             TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    FOREIGN KEY (owner_id, tenant_id)   REFERENCES tenant (owner_id, id),
    FOREIGN KEY (owner_id, lease_id)    REFERENCES lease (owner_id, id),
    FOREIGN KEY (owner_id, rent_due_id) REFERENCES rent_due (owner_id, id),
    FOREIGN KEY (owner_id, payment_id)  REFERENCES payment (owner_id, id)
);
-- Idempotence : une relance donnée n'est créée qu'une fois par échéance et canal
CREATE UNIQUE INDEX ux_notification_reminder
    ON notification (owner_id, rent_due_id, kind, channel) WHERE rent_due_id IS NOT NULL;
CREATE UNIQUE INDEX ux_notification_receipt
    ON notification (owner_id, payment_id, kind, channel) WHERE payment_id IS NOT NULL;
-- File de travail des workers (traitement transverse, sans filtre owner_id)
CREATE INDEX ix_notification_pending ON notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_provider_msg ON notification (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 8. Schéma d'archive : baux clos et données associées
--    (copie de structure sans FK ; remplie par le job d'archivage)
-- ---------------------------------------------------------------------

CREATE TABLE archive.lease              (LIKE public.lease              INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE archive.rent_due           (LIKE public.rent_due           INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE archive.payment            (LIKE public.payment            INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE archive.receipt            (LIKE public.receipt            INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE archive.deposit_settlement (LIKE public.deposit_settlement INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE archive.deposit_deduction  (LIKE public.deposit_deduction  INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

ALTER TABLE archive.lease              ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.rent_due           ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.payment            ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.receipt            ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.deposit_settlement ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.deposit_deduction  ADD PRIMARY KEY (id), ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX ix_arch_lease_owner      ON archive.lease              (owner_id, closed_at);
CREATE INDEX ix_arch_rent_due_owner   ON archive.rent_due           (owner_id, lease_id);
CREATE INDEX ix_arch_payment_owner    ON archive.payment            (owner_id, lease_id);
CREATE INDEX ix_arch_receipt_owner    ON archive.receipt            (owner_id, payment_id);
CREATE INDEX ix_arch_settlement_owner ON archive.deposit_settlement (owner_id, lease_id);
CREATE INDEX ix_arch_deduction_owner  ON archive.deposit_deduction  (owner_id, settlement_id);