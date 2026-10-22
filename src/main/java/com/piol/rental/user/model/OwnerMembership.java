package com.piol.rental.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Rattachement d'un utilisateur à un espace, avec son rôle dans cet espace. */
@Entity
@Table(name = "owner_membership")
@Getter
@Setter
@NoArgsConstructor
public class OwnerMembership {

    @EmbeddedId
    private OwnerMembershipId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OwnerMembership(OwnerMembershipId id, MembershipRole role) {
        this.id = id;
        this.role = role;
    }
}
