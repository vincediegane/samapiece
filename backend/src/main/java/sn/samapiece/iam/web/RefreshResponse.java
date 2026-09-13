package sn.samapiece.iam.web;

public record RefreshResponse(String accessToken, long expiresIn) {
}
