package com.faforever.client.preferences;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.extern.slf4j.Slf4j;

@Slf4j

public class LoginPrefs {
  private final StringProperty refreshToken;

  public LoginPrefs() {
    refreshToken = new SimpleStringProperty();
  }

  public String getRefreshToken() {
    return refreshToken.get();
  }

  public LoginPrefs setRefreshToken(String refreshToken) {
    this.refreshToken.set(refreshToken);
    return this;
  }

  public StringProperty refreshTokenProperty() {
    return refreshToken;
  }
}
