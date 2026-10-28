package com.enterprise.chat.controller;

import com.enterprise.chat.domain.dto.ChatMessageEvent;
import com.enterprise.chat.service.ChatHistoryService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/channels")
public class ChatHistoryController {
  private final ChatHistoryService service;

  public ChatHistoryController(ChatHistoryService service) {
    this.service = service;
  }

  @GetMapping("/{channel}/messages")
  public List<ChatMessageEvent> history(
      @PathVariable UUID channel,
      @RequestParam String month,
      @RequestParam(required = false) UUID before,
      @RequestParam(defaultValue = "50") int limit) {
    return service.history(channel, month, before, limit);
  }
}
