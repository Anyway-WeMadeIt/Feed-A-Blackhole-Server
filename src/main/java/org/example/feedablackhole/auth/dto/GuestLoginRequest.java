package org.example.feedablackhole.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestLoginRequest(
        @NotBlank @Size(max = 255) String guestId,
        @NotBlank @Size(max = 128) String guestSecret) {
}
