package com.piol.rental.user.service;

import com.piol.rental.user.model.AccountStatus;
import com.piol.rental.user.model.MembershipRole;

/** Droit d'un utilisateur sur un espace : son rôle et l'état de l'espace. */
public record SpaceAccess(MembershipRole role, AccountStatus spaceStatus) {}
