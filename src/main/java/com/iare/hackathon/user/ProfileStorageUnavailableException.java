package com.iare.hackathon.user;

public class ProfileStorageUnavailableException extends RuntimeException {
    public ProfileStorageUnavailableException() { super("Account storage is temporarily unavailable. Please try again later."); }
}
