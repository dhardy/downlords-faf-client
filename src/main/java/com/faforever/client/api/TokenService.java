package com.faforever.client.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

@Service
@Slf4j
public class TokenService {
  private final TokenServiceProperties tokenServiceProperties;
  private final RestTemplate simpleRestTemplate;
  private OAuth2AccessToken tokenCache;

  public TokenService(TokenServiceProperties tokenServiceProperties) {
    this.tokenServiceProperties = tokenServiceProperties;

    simpleRestTemplate = new RestTemplateBuilder().
        build();
  }

  public OAuth2AccessToken getRefreshedToken() {
    if (tokenCache == null || tokenCache.isExpired()) {
      log.debug("Token expired, fetching new token");
      refreshOAuthToken();
    } else {
      log.debug("Token still valid for {} seconds", tokenCache.getExpiresIn());
    }

    return tokenCache;
  }

  public OAuth2AccessToken loginWithAuthorizationCode(String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));

    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    map.add("code", code);
    map.add("client_id", "faf-ng-client");
    map.add("redirect_uri", "https://test.faforever.com/callback");
    map.add("grant_type", "authorization_code");
    map.add("client_secret", "banana");

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

    tokenCache = simpleRestTemplate.postForObject(
        "https://hydra.test.faforever.com/oauth2/token",
        request,
        OAuth2AccessToken.class
    );
    return tokenCache;
  }

  private void refreshOAuthToken() {
    tokenCache = loginWithRefreshToken(tokenCache.getRefreshToken().getValue());
  }

  public OAuth2AccessToken loginWithRefreshToken(String refreshToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));

    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    map.add("refresh_token", refreshToken);
    map.add("client_id", "faf-ng-client");
    map.add("redirect_uri", "https://test.faforever.com/callback");
    map.add("grant_type", "refresh_token");
    map.add("client_secret", "banana");

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

    tokenCache = simpleRestTemplate.postForObject(
        "https://hydra.test.faforever.com/oauth2/token",
        request,
        OAuth2AccessToken.class
    );
    return tokenCache;
  }
}