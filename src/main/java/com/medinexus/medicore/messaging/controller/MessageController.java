package com.medinexus.medicore.messaging.controller;

import java.util.Map;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.messaging.dto.MessageSentResponse;
import com.medinexus.medicore.messaging.dto.MessageInboxResponse;
import com.medinexus.medicore.messaging.dto.MessageConversationResponse;
import com.medinexus.medicore.messaging.dto.MessageHistoryResponse;
import com.medinexus.medicore.messaging.dto.MessageRecipientResponse;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.dto.ReplyMessageRequest;
import com.medinexus.medicore.messaging.service.MessageRecipientNotFoundException;
import com.medinexus.medicore.messaging.service.MessageService;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    @GetMapping("/recipients")
    public List<MessageRecipientResponse> recipients(@AuthenticationPrincipal UserDto principal) {
        return service.recipients(principal);
    }

    @GetMapping("/conversations")
    public List<MessageConversationResponse> conversations(@AuthenticationPrincipal UserDto principal) {
        return service.conversations(principal);
    }

    @GetMapping("/conversations/{recipientId}")
    public List<MessageHistoryResponse> thread(@AuthenticationPrincipal UserDto principal,
            @PathVariable Long recipientId) {
        return service.thread(principal, recipientId);
    }

    @GetMapping("/inbox")
    public List<MessageInboxResponse> inbox(@AuthenticationPrincipal UserDto principal) {
        return service.inbox(principal);
    }

    @GetMapping("/inbox/{patientId}")
    public List<MessageHistoryResponse> inboxThread(@AuthenticationPrincipal UserDto principal,
            @PathVariable Long patientId) {
        return service.inboxThread(principal, patientId);
    }

    @PostMapping("/inbox/{patientId}/reply")
    public ResponseEntity<MessageSentResponse> reply(@AuthenticationPrincipal UserDto principal,
            @PathVariable Long patientId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(service.reply(principal, patientId, ReplyMessageRequest.from(body)));
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
