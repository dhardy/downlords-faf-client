package com.faforever.client.user;

import com.faforever.client.api.TokenService;
import com.faforever.client.login.LoginFailedException;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.remote.domain.LoginMessage;
import com.faforever.client.remote.domain.NoticeMessage;
import com.faforever.client.user.event.ApiAuthorizedEvent;
import com.faforever.client.user.event.HydraAuthorizedEvent;
import com.faforever.client.user.event.LogOutRequestEvent;
import com.faforever.client.user.event.LoggedOutEvent;
import com.faforever.client.user.event.LoginSuccessEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Lazy
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements InitializingBean {

  private final StringProperty username = new SimpleStringProperty();

  private final FafService fafService;
  private final PreferencesService preferencesService;
  private final EventBus eventBus;
  private final TokenService tokenService;

  private String password;
  private Integer userId;
  private CompletableFuture<Void> loginFuture;
  @Getter
  private String state;

  public String getHydraUrl() {
    state = RandomStringUtils.randomAlphanumeric(50, 100);
    return String.format("https://hydra.test.faforever.com/oauth2/auth?response_type=code&client_id=faf-ng-client" +
        "&state=%s&redirect_uri=https://test.faforever.com/callback" +
        "&scope=openid offline public_profile write_account_data create_user", state);
  }

  public CompletableFuture<Void> login(String code) {
    loginFuture = CompletableFuture.runAsync(() -> {
      tokenService.loginWithAuthorizationCode(code);
    }).thenRunAsync(() -> eventBus.post(new HydraAuthorizedEvent()))
        .exceptionally(throwable -> {
          log.error("Error logging in", throwable);
          return null;
        });
    return loginFuture;
  }


  public String getUsername() {
    return username.get();
  }


  public String getPassword() {
    return password;
  }

  public Integer getUserId() {
    return userId;
  }


  public void cancelLogin() {
    if (loginFuture != null) {
      loginFuture.toCompletableFuture().cancel(true);
      loginFuture = null;
      fafService.disconnect();
    }
  }

  private void onLoginError(NoticeMessage noticeMessage) {
    if (loginFuture != null) {
      loginFuture.toCompletableFuture().completeExceptionally(new LoginFailedException(noticeMessage.getText()));
      loginFuture = null;
      fafService.disconnect();
    }
  }

  public void logOut() {
    log.info("Logging out");
    fafService.disconnect();
    eventBus.post(new LoggedOutEvent());
    preferencesService.getPreferences().getLogin().setAutoLogin(false);
  }

  @Override
  public void afterPropertiesSet() {
    fafService.addOnMessageListener(LoginMessage.class, loginInfo -> userId = loginInfo.getId());
    fafService.addOnMessageListener(NoticeMessage.class, this::onLoginError);
    eventBus.register(this);
  }

  @Subscribe
  public void onApiAuthorizedEvent(ApiAuthorizedEvent event) {
    fafService.getCurrentPlayer().thenAccept(me -> {
      username.set(me.getUserName());
      userId = Integer.parseInt(me.getUserId());
      eventBus.post(new LoginSuccessEvent());
    });
  }

  @Subscribe
  public void onLogoutRequestEvent(LogOutRequestEvent event) {
    logOut();
  }
}
