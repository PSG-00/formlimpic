package com.formlimpic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class KakaoAuthService {
    private static final Logger log = LoggerFactory.getLogger(KakaoAuthService.class);
    private static final String KAKAO_AUTH_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;

    @Autowired
    public KakaoAuthService(
            @Value("${kakao.client-id:}") String clientId,
            @Value("${kakao.client-secret:}") String clientSecret) {
        this.clientId = clientId != null ? clientId.trim() : "";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "";
        this.restClient = RestClient.builder().build();
    }

    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    public String buildAuthorizeUrl(String redirectUri) {
        if (!isConfigured()) {
            throw new IllegalStateException("카카오 로그인이 설정되어 있지 않습니다.");
        }
        return KAKAO_AUTH_URL + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
    }

    public KakaoUser fetchUserInfo(String code, String redirectUri) {
        if (!isConfigured()) {
            throw new IllegalStateException("카카오 로그인이 설정되어 있지 않습니다.");
        }

        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code")
                .append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                .append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8))
                .append("&code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));

        if (!clientSecret.isBlank()) {
            body.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenResponse = restClient.post()
                .uri(KAKAO_TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .body(body.toString())
                .retrieve()
                .body(Map.class);

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            throw new IllegalStateException("카카오 인증 토큰을 발급받지 못했습니다.");
        }

        String accessToken = (String) tokenResponse.get("access_token");

        @SuppressWarnings("unchecked")
        Map<String, Object> userResponse = restClient.get()
                .uri(KAKAO_USER_INFO_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);

        if (userResponse == null || !userResponse.containsKey("id")) {
            throw new IllegalStateException("카카오 사용자 정보를 가져오지 못했습니다.");
        }

        String kakaoId = String.valueOf(userResponse.get("id"));
        String nickname = "카카오사용자";

        if (userResponse.get("properties") instanceof Map<?, ?> props && props.containsKey("nickname")) {
            nickname = String.valueOf(props.get("nickname"));
        } else if (userResponse.get("kakao_account") instanceof Map<?, ?> account) {
            if (account.get("profile") instanceof Map<?, ?> profile && profile.containsKey("nickname")) {
                nickname = String.valueOf(profile.get("nickname"));
            }
        }

        return new KakaoUser(kakaoId, nickname);
    }

    public record KakaoUser(String id, String nickname) {}
}
