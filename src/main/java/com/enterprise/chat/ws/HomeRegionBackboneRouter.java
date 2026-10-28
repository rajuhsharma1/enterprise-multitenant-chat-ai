package com.enterprise.chat.ws;

import com.enterprise.chat.domain.dto.*;
import com.enterprise.chat.messaging.ChatKafkaProducer;
import com.enterprise.chat.security.TenantContext;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HomeRegionBackboneRouter {
  private final ChatKafkaProducer producer;
  private final RestClient http;
  private final String localRegion, serviceToken;
  private final Map<String, String> regions;

  public HomeRegionBackboneRouter(
      ChatKafkaProducer producer,
      @Value("${app.region}") String region,
      @Value("${app.backbone.regions}") String endpoints,
      @Value("${app.backbone.token:}") String token) {
    this.producer = producer;
    this.localRegion = region;
    this.serviceToken = token;
    this.regions = new HashMap<>();
    for (String pair : endpoints.split(",")) {
      var parts = pair.trim().split("=", 2);
      if (parts.length != 2) throw new IllegalArgumentException("Invalid region map");
      regions.put(parts[0], parts[1]);
    }
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    factory.setReadTimeout(Duration.ofSeconds(20));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  public ChatMessageEvent route(InboundWsFrame frame, String bearer) {
    String home = TenantContext.principal().homeRegion();
    if (home.equals(localRegion)) return producer.accept(frame);
    return http.post()
        .uri(endpoint(home) + "/internal/messages")
        .headers(h -> h.setBearerAuth(bearer))
        .body(frame)
        .retrieve()
        .body(ChatMessageEvent.class);
  }

  private String endpoint(String region) {
    String endpoint = regions.get(region);
    if (endpoint == null) throw new IllegalStateException("Home region is not configured");
    return endpoint;
  }

  public void fanout(RedisCrossRegionFanoutListener.Fanout event, String homeRegion) {
    if (!localRegion.equals(homeRegion))
      throw new SecurityException("Only home region may fan out");
    for (var entry : regions.entrySet()) {
      if (entry.getKey().equals(localRegion)) continue;
      if (serviceToken.isBlank()) throw new IllegalStateException("Missing backbone service JWT");
      http.post()
          .uri(entry.getValue() + "/internal/fanout")
          .headers(h -> h.setBearerAuth(serviceToken))
          .body(event)
          .retrieve()
          .toBodilessEntity();
    }
  }
}
