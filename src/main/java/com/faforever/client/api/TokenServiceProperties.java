package com.faforever.client.api;

import lombok.Data;
import org.springframework.stereotype.Component;

@Component
@Data
public class TokenServiceProperties {
  private String aadB2bUrl;
  private String aadB2bClientId;
  private String aadB2bResource;
}