package com.piol.rental.user.dto;

import com.piol.rental.user.model.MembershipRole;

import java.util.UUID;

public record SpaceRef(
        UUID id,
        String ownerName,
        MembershipRole role

) {
}
