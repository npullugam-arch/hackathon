package com.iare.hackathon.wallet;

import org.springframework.http.HttpStatus;

public class WalletException extends RuntimeException {
    final HttpStatus status;
    public WalletException(HttpStatus status, String message) { super(message); this.status = status; }
    static WalletException invalid(String message) { return new WalletException(HttpStatus.BAD_REQUEST, message); }
    static WalletException unavailable(String message) { return new WalletException(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
