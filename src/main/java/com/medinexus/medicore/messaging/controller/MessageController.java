package com.medinexus.medicore.messaging.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.messaging.dto.MessageSentResponse;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.service.MessageRecipientNotFoundException;
import com.medinexus.medicore.messaging.service.MessageService;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MessageSentResponse> send(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(service.send(principal, SendMessageRequest.from(body)));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }

    @ExceptionHandler(MessageRecipientNotFoundException.class)
    public ResponseEntity<Void> notFound() { return ResponseEntity.notFound().build(); }
}
