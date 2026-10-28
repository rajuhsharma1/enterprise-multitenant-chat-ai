package com.enterprise.chat.controller;

import com.enterprise.chat.domain.dto.*;
import com.enterprise.chat.messaging.ChatKafkaProducer;
import com.enterprise.chat.repository.OrganizationRepository;
import com.enterprise.chat.security.TenantContext;
import com.enterprise.chat.ws.RedisCrossRegionFanoutListener;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class InternalBackboneController {
  private final ChatKafkaProducer producer;
  private final RedisCrossRegionFanoutListener fanout;
  private final FanoutAuthorization authorization;

  public InternalBackboneController(
      ChatKafkaProducer producer,
      RedisCrossRegionFanoutListener fanout,
      FanoutAuthorization authorization) {
    this.producer = producer;
    this.fanout = fanout;
    this.authorization = authorization;
  }

  @PostMapping("/messages")
  public ChatMessageEvent message(@Valid @RequestBody InboundWsFrame frame) {
    return producer.accept(frame);
  }

  @PostMapping("/fanout")
  public void fanout(
      @RequestBody RedisCrossRegionFanoutListener.Fanout event, JwtAuthenticationToken auth) {
    if (event.orgId() == null
        || event.channelId() == null
        || event.payload() == null
        || event.payload().length() > 131072) throw new IllegalArgumentException("Invalid fanout");
    TenantContext.withOrg(
        event.orgId(),
        () -> {
          authorization.check(auth.getToken().getClaimAsString("source_region"));
          return null;
        });
    fanout.publish(event);
  }

  @Service
  public static class FanoutAuthorization {
    private final OrganizationRepository organizations;

    public FanoutAuthorization(OrganizationRepository organizations) {
      this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public void check(String sourceRegion) {
      var org =
          organizations
              .findById(TenantContext.orgId())
              .orElseThrow(com.enterprise.chat.security.ChannelRbacValidator::denied);
      if (!org.getHomeRegion().equals(sourceRegion))
        throw com.enterprise.chat.security.ChannelRbacValidator.denied();
    }
  }
}
