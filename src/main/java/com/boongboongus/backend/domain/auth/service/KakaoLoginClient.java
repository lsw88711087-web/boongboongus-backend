package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;

@Component
class KakaoLoginClient {
    private final KakaoLoginProperties kakao;
    private final RestClient http;

    KakaoLoginClient(KakaoLoginProperties kakao) {
        this.kakao = kakao;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    long authenticate(String code, String redirectUri) {
        if (!StringUtils.hasText(kakao.restApiKey())) {
            throw TokenExchangeException.unavailable();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakao.restApiKey());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (StringUtils.hasText(kakao.clientSecret())) {
            form.add("client_secret", kakao.clientSecret());
        }

        try {
            var token = http.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(KakaoToken.class);
            if (token == null || !StringUtils.hasText(token.accessToken())) {
                throw TokenExchangeException.unavailable();
            }

            var user = http.get().uri("https://kapi.kakao.com/v2/user/me")
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve().body(KakaoUser.class);
            if (user == null || user.id() == null) {
                throw TokenExchangeException.unavailable();
            }
            return user.id();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                throw TokenExchangeException.verificationFailed();
            }
            throw TokenExchangeException.unavailable();
        } catch (RestClientException exception) {
            throw TokenExchangeException.unavailable();
        }
    }

    private record KakaoToken(@JsonProperty("access_token") String accessToken) {}
    private record KakaoUser(Long id) {}
}
