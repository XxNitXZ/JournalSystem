package com.medinexus.medicore.messaging.service;

public class MessageRecipientNotFoundException extends RuntimeException {
    public MessageRecipientNotFoundException() {
        super("Recipient not found");
    }
}
