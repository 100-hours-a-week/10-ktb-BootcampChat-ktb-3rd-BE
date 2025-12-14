package com.ktb.chatapp.service;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ReadAckEntry {
    private final String messageId;
    private final String userId;
    private final LocalDateTime readAt;
}