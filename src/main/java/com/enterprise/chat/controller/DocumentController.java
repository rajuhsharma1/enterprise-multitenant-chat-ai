package com.enterprise.chat.controller;

import com.enterprise.chat.domain.dto.*;
import com.enterprise.chat.service.TimedDocumentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final TimedDocumentService service;

  public DocumentController(TimedDocumentService service) {
    this.service = service;
  }

  @PostMapping("/upload-url")
  public PresignedUrlResponse upload(@Valid @RequestBody PresignedUploadRequest request) {
    return service.upload(request);
  }

  @PostMapping("/{id}/complete")
  public void complete(@PathVariable UUID id) {
    service.complete(id);
  }

  @GetMapping("/{id}/download-url")
  public PresignedUrlResponse download(@PathVariable UUID id) {
    return service.download(id);
  }

  @org.springframework.web.bind.annotation.RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> badRequest() {
      return org.springframework.http.ResponseEntity.badRequest()
          .body(java.util.Map.of("error", "Invalid request"));
    }

    @ExceptionHandler({
      org.springframework.security.access.AccessDeniedException.class,
      SecurityException.class
    })
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> forbidden() {
      return org.springframework.http.ResponseEntity.status(403)
          .body(java.util.Map.of("error", "Resource unavailable"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> unavailable(
        IllegalStateException exception) {
      org.slf4j.LoggerFactory.getLogger(Errors.class).error("Service operation failed", exception);
      return org.springframework.http.ResponseEntity.status(503)
          .body(java.util.Map.of("error", "Temporarily unavailable"));
    }
  }
}
