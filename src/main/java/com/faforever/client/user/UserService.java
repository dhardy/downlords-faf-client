package com.faforever.client.user;

import com.faforever.client.api.SessionExpiredEvent;
import com.faforever.client.api.TokenService;
import com.faforever.client.api.TokenService.AuthenticationExpiredException;
import com.faforever.client.i18n.I18n;
import com.faforever.client.login.LoginFailedException;
import com.faforever.client.notification.ImmediateNotification;
import com.faforever.client.notification.NotificationService;
import com.faforever.client.notification.Severity;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.remote.domain.LoginMessage;
import com.faforever.client.remote.domain.NoticeMessage;
import com.faforever.client.user.event.ApiAuthorizedEvent;
import com.faforever.client.user.event.HydraAuthorizedEvent;
import com.faforever.client.user.event.LogOutRequestEvent;
import com.faforever.client.user.event.LoggedOutEvent;
import com.faforever.client.user.event.LoginSuccessEvent;
import com.faforever.commons.api.dto.MeResult;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Lazy
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements InitializingBean {

  private final ObjectProperty<MeResult> ownUser = new SimpleObjectProperty<>();

  private final FafService fafService;
  private final PreferencesService preferencesService;
  private final EventBus eventBus;
  private final TokenService tokenService;
  private final NotificationService notificationService;
  private final I18n i18n;

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
    loginFuture = CompletableFuture.runAsync(() -> tokenService.loginWithAuthorizationCode(code))
        .thenRunAsync(() -> eventBus.post(new HydraAuthorizedEvent()))
        .exceptionally(throwable -> {
          log.error("Error logging in", throwable);
          return null;
        });
    return loginFuture;
  }

  public CompletableFuture<Void> loginWithRefreshToken(String refreshToken) {
    loginFuture = CompletableFuture.runAsync(() -> {
      try {
        tokenService.loginWithRefreshToken(refreshToken);
      } catch (AuthenticationExpiredException e) {
        throw new CompletionException(e);
      }
    })
        .thenRunAsync(() -> eventBus.post(new HydraAuthorizedEvent()))
        .exceptionally(throwable -> {
          if (throwable.getCause() instanceof AuthenticationExpiredException) {
            log.info("Refresh token expired");
          } else {
            log.error("Cannot login with refresh token", throwable);
          }
          return null;
        });
    return loginFuture;
  }


  public String getUsername() {
    return ownUser.get().getUserName();
  }


  public String getPassword() {
    return "foo";
  }

  public Integer getUserId() {
    return Integer.parseInt(ownUser.get().getUserId());
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
    preferencesService.getPreferences().getLogin().setRefreshToken(null);
    preferencesService.storeInBackground();
  }

  @Subscribe
  public void onSessionExpired(SessionExpiredEvent sessionExpiredEvent) {
    if (loginFuture.isDone()) {
      logOut();
      notificationService.addNotification(new ImmediateNotification(i18n.get("session.expired.title"), i18n.get("session.expired.message"), Severity.INFO));
    }
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
      setOwnUser(me);
      eventBus.post(new LoginSuccessEvent());
    });
  }

  @Subscribe
  public void onLogoutRequestEvent(LogOutRequestEvent event) {
    logOut();
  }

  public MeResult getOwnUser() {
    return ownUser.get();
  }

  public void setOwnUser(MeResult ownUser) {
    this.ownUser.set(ownUser);
  }

  public ObjectProperty<MeResult> ownUserProperty() {
    return ownUser;
  }
}
