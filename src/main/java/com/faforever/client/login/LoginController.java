package com.faforever.client.login;

import com.faforever.client.config.ClientProperties;
import com.faforever.client.config.ClientProperties.Irc;
import com.faforever.client.config.ClientProperties.Replay;
import com.faforever.client.config.ClientProperties.Server;
import com.faforever.client.fx.Controller;
import com.faforever.client.fx.JavaFxUtil;
import com.faforever.client.fx.PlatformService;
import com.faforever.client.fx.WebViewConfigurer;
import com.faforever.client.i18n.I18n;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.update.ClientConfiguration;
import com.faforever.client.update.ClientConfiguration.Endpoints;
import com.faforever.client.update.ClientUpdateService;
import com.faforever.client.update.DownloadUpdateTask;
import com.faforever.client.update.UpdateInfo;
import com.faforever.client.update.Version;
import com.faforever.client.user.UserService;
import com.google.common.annotations.VisibleForTesting;
import com.sun.javafx.webkit.Accessor;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.web.WebView;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.concurrent.CompletableFuture;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
@RequiredArgsConstructor
public class LoginController implements Controller<Pane> {

  private final UserService userService;
  private final PreferencesService preferencesService;
  private final PlatformService platformService;
  private final ClientProperties clientProperties;
  private final I18n i18n;
  private final ClientUpdateService clientUpdateService;
  private final WebViewConfigurer webViewConfigurer;
  private CompletableFuture<Void> initializeFuture;

  public Pane errorPane;
  public Pane loginFormPane;
  public WebView loginWebView;
  public ComboBox<ClientConfiguration.Endpoints> environmentComboBox;
  public Button downloadUpdateButton;
  public Label loginErrorLabel;
  public Pane loginRoot;
  public GridPane serverConfigPane;
  public TextField serverHostField;
  public TextField serverPortField;
  public TextField replayServerHostField;
  public TextField replayServerPortField;
  public TextField ircServerHostField;
  public TextField ircServerPortField;
  public TextField apiBaseUrlField;
  public Button serverStatusButton;

  @VisibleForTesting
  CompletableFuture<UpdateInfo> updateInfoFuture;

  public void initialize() {
    JavaFxUtil.bindManagedToVisible(downloadUpdateButton, loginErrorLabel, loginFormPane, loginWebView,
        serverConfigPane, serverStatusButton);
    updateInfoFuture = clientUpdateService.getNewestUpdate();

    downloadUpdateButton.setVisible(false);
    errorPane.setVisible(false);
    loginErrorLabel.setVisible(false);
    serverConfigPane.setVisible(false);
    serverStatusButton.setVisible(clientProperties.getStatusPageUrl() != null);

    // fallback values if configuration is not read from remote
    populateEndpointFields(
        clientProperties.getServer().getHost(),
        clientProperties.getServer().getPort(),
        clientProperties.getReplay().getRemoteHost(),
        clientProperties.getReplay().getRemotePort(),
        clientProperties.getIrc().getHost(),
        clientProperties.getIrc().getPort(),
        clientProperties.getApi().getBaseUrl()
    );

    environmentComboBox.setConverter(new StringConverter<>() {
      @Override
      public String toString(Endpoints endpoints) {
        return endpoints == null ? null : endpoints.getName();
      }

      @Override
      public Endpoints fromString(String string) {
        throw new UnsupportedOperationException("Not supported");
      }
    });

    ReadOnlyObjectProperty<Endpoints> selectedEndpointProperty = environmentComboBox.getSelectionModel().selectedItemProperty();

    selectedEndpointProperty.addListener(observable -> {
      Endpoints endpoints = environmentComboBox.getSelectionModel().getSelectedItem();

      if (endpoints == null) {
        return;
      }

      // TODO: Use the proper url for the endpoint
//      loginWebView.getEngine().load(userService.getHydraUrl(endpoints.getRedirect()));
      loginWebView.getEngine().load(userService.getHydraUrl());

      serverHostField.setText(endpoints.getLobby().getHost());
      serverPortField.setText(String.valueOf(endpoints.getLobby().getPort()));

      replayServerHostField.setText(endpoints.getLiveReplay().getHost());
      replayServerPortField.setText(String.valueOf(endpoints.getLiveReplay().getPort()));

      ircServerHostField.setText(endpoints.getIrc().getHost());
      ircServerPortField.setText(String.valueOf(endpoints.getIrc().getPort()));

      apiBaseUrlField.setText(endpoints.getApi().getUrl());
    });


    if (clientProperties.isUseRemotePreferences()) {
      initializeFuture = preferencesService.getRemotePreferencesAsync()
          .thenAccept(clientConfiguration -> {
            Endpoints defaultEndpoint = clientConfiguration.getEndpoints().get(0);

            Server server = clientProperties.getServer();
            server.setHost(defaultEndpoint.getLobby().getHost());
            server.setPort(defaultEndpoint.getLobby().getPort());

            Replay replay = clientProperties.getReplay();
            replay.setRemoteHost(defaultEndpoint.getLiveReplay().getHost());
            replay.setRemotePort(defaultEndpoint.getLiveReplay().getPort());

            Irc irc = clientProperties.getIrc();
            irc.setHost(defaultEndpoint.getIrc().getHost());
            irc.setPort(defaultEndpoint.getIrc().getPort());

            clientProperties.getApi().setBaseUrl(defaultEndpoint.getApi().getUrl());

            String minimumVersion = clientConfiguration.getLatestRelease().getMinimumVersion();
            boolean shouldUpdate = false;
            try {
              shouldUpdate = Version.shouldUpdate(Version.getCurrentVersion(), minimumVersion);
            } catch (Exception e) {
              log.error("Error occurred checking for update", e);
            }

            shouldUpdate = true;

            if (minimumVersion != null && shouldUpdate) {
              JavaFxUtil.runLater(() -> showClientOutdatedPane(minimumVersion));
            }

            JavaFxUtil.runLater(() -> {
              environmentComboBox.getItems().addAll(clientConfiguration.getEndpoints());
              environmentComboBox.getSelectionModel().select(defaultEndpoint);
            });
          }).exceptionally(throwable -> {
            log.warn("Could not read remote preferences", throwable);
            return null;
          }).thenRunAsync(() -> {
            String refreshToken = preferencesService.getPreferences().getLogin().getRefreshToken();
            if (refreshToken != null) {
              userService.loginWithRefreshToken(refreshToken);
            }
          });
    } else {
      initializeFuture = CompletableFuture.completedFuture(null);
    }

    webViewConfigurer.configureWebView(loginWebView);
    Accessor.getPageFor(loginWebView.getEngine()).setBackgroundColor(Color.BLACK.getRGB());
    loginWebView.getEngine().locationProperty().addListener((observable, oldValue, newValue) -> {
      int codeIndex = newValue.indexOf("code=");
      if (codeIndex >= 0) {
        int codeEnd = newValue.indexOf("&", codeIndex);
        String code = newValue.substring(codeIndex + 5, codeEnd);
        int stateIndex = newValue.indexOf("state=");
        int stateEnd = newValue.indexOf("&", stateIndex);
        String reportedState;
        if (stateEnd > 0) {
          reportedState = newValue.substring(stateIndex + 6, stateEnd);
        } else {
          reportedState = newValue.substring(stateIndex + 6);
        }
        String state = userService.getState();

        if (!state.equals(reportedState)) {
          log.warn("States do not match We are under attack!");
          // TODO: Report to the user take action something
        }

        Server server = clientProperties.getServer();
        server.setHost(serverHostField.getText());
        server.setPort(Integer.parseInt(serverPortField.getText()));

        Replay replay = clientProperties.getReplay();
        replay.setRemoteHost(replayServerHostField.getText());
        replay.setRemotePort(Integer.parseInt(replayServerPortField.getText()));

        Irc irc = clientProperties.getIrc();
        irc.setHost(ircServerHostField.getText());
        irc.setPort(Integer.parseInt(ircServerPortField.getText()));

        clientProperties.getApi().setBaseUrl(apiBaseUrlField.getText());

        initializeFuture.join();

        if (!loginWebView.isVisible()) {
          return;
        }

        userService.login(code);
      }
    });
  }

  private void showClientOutdatedPane(String minimumVersion) {
    JavaFxUtil.runLater(() -> {
      errorPane.setVisible(true);
      loginErrorLabel.setText(i18n.get("login.clientTooOldError", Version.getCurrentVersion(), minimumVersion));
      loginErrorLabel.setVisible(true);
      downloadUpdateButton.setVisible(true);
      loginFormPane.setDisable(true);
      loginFormPane.setVisible(false);
      loginWebView.setVisible(false);
      log.warn("Update required");
    });
  }

  private void populateEndpointFields(
      String serverHost,
      int serverPort,
      String replayServerHost,
      int replayServerPort,
      String ircServerHost,
      int ircServerPort,
      String apiBaseUrl
  ) {
    JavaFxUtil.runLater(() -> {
      serverHostField.setText(serverHost);
      serverPortField.setText(String.valueOf(serverPort));
      replayServerHostField.setText(replayServerHost);
      replayServerPortField.setText(String.valueOf(replayServerPort));
      ircServerHostField.setText(ircServerHost);
      ircServerPortField.setText(String.valueOf(ircServerPort));
      apiBaseUrlField.setText(apiBaseUrl);
    });
  }


  public void onDownloadUpdateButtonClicked() {
    downloadUpdateButton.setOnAction(event -> {
    });
    log.info("Downloading update");
    updateInfoFuture
        .thenAccept(updateInfo -> {
          DownloadUpdateTask downloadUpdateTask = clientUpdateService.downloadAndInstallInBackground(updateInfo);

          if (downloadUpdateTask != null) {
            downloadUpdateButton.textProperty().bind(
                Bindings.createStringBinding(() -> downloadUpdateTask.getProgress() == -1 ?
                        i18n.get("login.button.downloadPreparing") :
                        i18n.get("login.button.downloadProgress", downloadUpdateTask.getProgress()),
                    downloadUpdateTask.progressProperty()));
          }
        });
  }

  public Pane getRoot() {
    return loginRoot;
  }

  public void onMouseClicked(MouseEvent event) {
    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
      serverConfigPane.setVisible(true);
    }
  }

  public void seeServerStatus() {
    String statusPageUrl = clientProperties.getStatusPageUrl();
    if (statusPageUrl == null) {
      return;
    }
    platformService.showDocument(statusPageUrl);
  }
}
