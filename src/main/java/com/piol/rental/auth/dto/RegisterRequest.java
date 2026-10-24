package com.piol.rental.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        // omettre si absent. Format international E.164, ex. +221771234567
        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Numéro au format international attendu (ex. +221771234567)")
        String phone,
        // BCrypt ne prend en compte que les 72 premiers octets
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
