package com.piol.rental.user.service;

/** Droit d'un utilisateur sur un espace : son rôle et l'état de l'espace. */
public record SpaceAccess(MembershipRole role, AccountStatus spaceStatus) {}
