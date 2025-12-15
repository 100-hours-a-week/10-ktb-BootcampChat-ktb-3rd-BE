package com.ktb.chatapp.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
public class SessionValidationResult {
    private boolean isValid;
    private String error;
    private String message;
    private SessionData session;

    private SessionValidationResult(
            boolean isValid,
            String error,
            String message,
            SessionData session
            ) {
        this.isValid = isValid;
        this.error = error;
        this.message = message;
        this.session = session;
    }

    public static SessionValidationResult valid() {
        return new SessionValidationResult(true, null, null, null);
    }

    public static SessionValidationResult valid(SessionData session) {
        SessionValidationResult result = new SessionValidationResult();
        result.isValid = true;
        result.session = session;
        return result;
    }
    
    public static SessionValidationResult invalid(String error, String message) {
        SessionValidationResult result = new SessionValidationResult();
        result.isValid = false;
        result.error = error;
        result.message = message;
        return result;
    }
}
