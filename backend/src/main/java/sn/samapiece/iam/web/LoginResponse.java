package sn.samapiece.iam.web;

public record LoginResponse(
        String accessToken, String refreshToken, long expiresIn, String role, String nom) {
}
