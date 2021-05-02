package com.faforever.client.api;

import com.faforever.client.preferences.PreferencesService;
import com.google.gson.Gson;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.jwt.JwtHelper;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collections;

@Service
@Slf4j
public class TokenService {
  private final TokenServiceProperties tokenServiceProperties;
  private final PreferencesService preferencesService;
  private final RestTemplate simpleRestTemplate;
  private OAuth2AccessToken tokenCache;

  public TokenService(TokenServiceProperties tokenServiceProperties, PreferencesService preferencesService) {
    this.tokenServiceProperties = tokenServiceProperties;
    this.preferencesService = preferencesService;

    simpleRestTemplate = new RestTemplateBuilder().
        build();
  }

  private Instant getExpireOfRefreshToke(String refreshToken) {
    String decode = JwtHelper.decode(refreshToken).getClaims();
    Gson gson = new Gson();
    RefreshToken refreshTokenObject = gson.fromJson(decode, RefreshToken.class);
    return Instant.ofEpochSecond(refreshTokenObject.getExp());
  }

  public OAuth2AccessToken getRefreshedToken() throws AuthenticationExpiredException {
    if (tokenCache != null && tokenCache.getRefreshToken() != null
        && getExpireOfRefreshToke(tokenCache.getRefreshToken().getValue()).isBefore(Instant.now())) {
      log.debug("Refresh Token expired");
      throw new AuthenticationExpiredException();
    }

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

    //TODO use configured values
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
    preferencesService.getPreferences().getLogin().setRefreshToken(tokenCache.getRefreshToken().toString());
    preferencesService.storeInBackground();
    return tokenCache;
  }

  private void refreshOAuthToken() throws AuthenticationExpiredException {
    tokenCache = loginWithRefreshToken(tokenCache.getRefreshToken().getValue());
  }

  public OAuth2AccessToken loginWithRefreshToken(String refreshToken) throws AuthenticationExpiredException {
    if (getExpireOfRefreshToke(refreshToken).isBefore(Instant.now())) {
      log.debug("Refresh Token expired");
      throw new AuthenticationExpiredException();
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));

    //TODO use configured values
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

    preferencesService.getPreferences().getLogin().setRefreshToken(tokenCache.getRefreshToken().toString());
    preferencesService.storeInBackground();
    return tokenCache;
  }

  @Data
  private static class RefreshToken {
    private long exp;
  }

  public static class AuthenticationExpiredException extends Exception {
    public AuthenticationExpiredException() {
      super("Session Expired");
    }
  }
}
