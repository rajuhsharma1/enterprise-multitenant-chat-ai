package com.enterprise.chat.service;

import com.enterprise.chat.domain.dto.ChatMessageEvent;
import com.enterprise.chat.repository.ScyllaChatRepository;
import com.enterprise.chat.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatHistoryService {
  private final ScyllaChatRepository history;
  private final ChannelRbacValidator rbac;
  private final KmsEnvelopeEncryptionService crypto;
  private final String region;

  public ChatHistoryService(
      ScyllaChatRepository history,
      ChannelRbacValidator rbac,
      KmsEnvelopeEncryptionService crypto,
      @Value("${app.region}") String region) {
    this.history = history;
    this.rbac = rbac;
    this.crypto = crypto;
    this.region = region;
  }

  public List<ChatMessageEvent> history(UUID channel, String month, UUID before, int limit) {
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    rbac.require(channel);
    if (!region.equals(p.homeRegion()))
      throw new IllegalArgumentException("Use the organization's home region for history");
    if (!month.matches("[0-9]{4}(0[1-9]|1[0-2])")
        || limit < 1
        || limit > 100
        || before != null && before.version() != 1)
      throw new IllegalArgumentException("Invalid history cursor");
    var rows =
        before == null
            ? history.latest(p.orgId(), channel, month, limit)
            : history.history(p.orgId(), channel, month, before, limit);
    return rows.stream()
        .map(
            r ->
                new ChatMessageEvent(
                    p.orgId(),
                    channel,
                    r.key().messageId(),
                    r.senderId(),
                    p.homeRegion(),
                    r.createdAt(),
                    crypto.decrypt(
                        p.orgId(),
                        p.kmsKeyArn(),
                        "message:" + channel + ":" + r.key().messageId(),
                        r.encryptedContent()),
                    r.documentId()))
        .toList();
  }
}
