package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private final ObjectProvider<UserProfileRepository> repositories;
    public UserProfileService(ObjectProvider<UserProfileRepository> repositories) { this.repositories = repositories; }
    public AuthUser synchronize(AuthUser verifiedUser) {
        try { return repository().upsert(verifiedUser).toAuthUser(); }
        catch (DataAccessException ex) { throw unavailable(); }
    }
    public Optional<AuthUser> findByUid(String verifiedUid) {
        try { return repository().findByFirebaseUid(verifiedUid).map(UserProfile::toAuthUser); }
        catch (DataAccessException ex) { throw unavailable(); }
    }
    private UserProfileRepository repository() {
        var repository = repositories.getIfAvailable();
        if (repository == null) throw new ProfileStorageUnavailableException();
        return repository;
    }
    private ProfileStorageUnavailableException unavailable() {
        log.warn("Supabase profile operation failed. Check database availability and server connection configuration.");
        return new ProfileStorageUnavailableException();
    }
}
