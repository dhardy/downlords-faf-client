package com.faforever.client.user;

import com.faforever.client.login.LoginFailedException;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.remote.domain.LoginMessage;
import com.faforever.client.remote.domain.NoticeMessage;
import com.faforever.client.task.CompletableTask;
import com.faforever.client.task.TaskService;
import com.faforever.client.user.event.LogOutRequestEvent;
import com.faforever.client.user.event.LoggedOutEvent;
import com.faforever.client.user.event.LoginSuccessEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Lazy
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements InitializingBean {

  private final StringProperty username = new SimpleStringProperty();

  private final FafService fafService;
  private final PreferencesService preferencesService;
  private final EventBus eventBus;
  private final ApplicationContext applicationContext;
  private final TaskService taskService;

  private String password;
  private Integer userId;
  private CompletableFuture<Void> loginFuture;
  @Getter
  private String state;

  public String getHydraUrl() {
    state = RandomStringUtils.randomAlphanumeric(50, 100);
    return String.format("https://hydra.test.faforever.com/oauth2/auth?response_type=code&client_id=faf-ng-client" +
        "&state=%s&redirect_uri=http://localhost:4200/index.html" +
        "&scope=openid offline public_profile write_account_data create_user", state);
  }

  public CompletableFuture<Void> login(String code) {
    return CompletableFuture.runAsync(() -> {
      CloseableHttpClient httpclient = HttpClients.createDefault();
      HttpPost httppost = new HttpPost("https://hydra.test.faforever.com/oauth2/token");

      // Request parameters and other properties.
      List<NameValuePair> params = new ArrayList<NameValuePair>(2);
      params.add(new BasicNameValuePair("code", code));
      params.add(new BasicNameValuePair("client_id", "faf-ng-client"));
      params.add(new BasicNameValuePair("redirect_uri", "http://localhost:4200/index.html"));
      params.add(new BasicNameValuePair("grant_type", "authorization_code"));
      params.add(new BasicNameValuePair("client_secret", "banana"));
      try {
        httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        throw new CompletionException(e);
      }

      //Execute and get the response.
      HttpResponse response = null;
      try {
        response = httpclient.execute(httppost);
      } catch (IOException e) {
        throw new CompletionException(e);
      }
      HttpEntity entity = response.getEntity();

      if (entity != null) {
        try (InputStream instream = entity.getContent()) {
          String string = new String(instream.readAllBytes());
          logger.info(string);
        } catch (IOException e) {
          throw new CompletionException(e);
        }
      }
      // TODO: implement ory hydra login with server
    }).exceptionally(throwable -> {
      logger.error("Error logging in", throwable);
      return null;
    });
  }

  public CompletableFuture<Void> login(String username, String password, boolean autoLogin) {
    this.password = password;

    preferencesService.getPreferences().getLogin()
        .setUsername(username)
        .setPassword(autoLogin ? password : null)
        .setAutoLogin(autoLogin);
    preferencesService.storeInBackground();

    loginFuture = fafService.connectAndLogIn(username, password)
        .thenAccept(loginInfo -> {
          userId = loginInfo.getId();

          // Because of different case (upper/lower)
          String login = loginInfo.getLogin();
          UserService.this.username.set(login);

          preferencesService.getPreferences().getLogin().setUsername(login);
          preferencesService.storeInBackground();

          eventBus.post(new LoginSuccessEvent(login, password, userId));
        })
        .whenComplete((aVoid, throwable) -> {
          if (throwable != null) {
            log.warn("Error during login", throwable);
            fafService.disconnect();
          }
          loginFuture = null;
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


  public CompletableTask<Void> changePassword(String currentPassword, String newPassword) {
    ChangePasswordTask changePasswordTask = applicationContext.getBean(ChangePasswordTask.class);
    changePasswordTask.setUsername(username.get());
    changePasswordTask.setCurrentPassword(currentPassword);
    changePasswordTask.setNewPassword(newPassword);

    return taskService.submitTask(changePasswordTask);
  }

  @Override
  public void afterPropertiesSet() {
    fafService.addOnMessageListener(LoginMessage.class, loginInfo -> userId = loginInfo.getId());
    fafService.addOnMessageListener(NoticeMessage.class, this::onLoginError);
    eventBus.register(this);
  }

  @Subscribe
  public void onLogoutRequestEvent(LogOutRequestEvent event) {
    logOut();
  }
}
