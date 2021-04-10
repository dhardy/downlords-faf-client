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
      tokenCache = refreshOAuthToken();
    } else {
      log.debug("Token still valid for {} seconds", tokenCache.getExpiresIn());
    }

    return tokenCache;
  }

  public OAuth2AccessToken loginWithCredentials(String username, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));

    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    map.add("grant_type", "password");
    map.add("resource", tokenServiceProperties.getAadB2bResource());
    map.add("client_id", tokenServiceProperties.getAadB2bClientId());
    map.add("username", username);
    map.add("password", password);

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

    return simpleRestTemplate.postForObject(
        tokenServiceProperties.getAadB2bUrl(),
        request,
        OAuth2AccessToken.class
    );
  }

  private OAuth2AccessToken refreshOAuthToken() {
    return loginWithRefreshToken(tokenCache.getRefreshToken().getValue());
  }

  public OAuth2AccessToken loginWithRefreshToken(String refreshToken) {
    // add code for fetching OAuth2 token from refresh token here
    return null;
  }
}