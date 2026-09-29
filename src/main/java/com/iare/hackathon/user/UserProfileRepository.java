package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import java.util.Optional;

public interface UserProfileRepository {
    UserProfile upsert(AuthUser verifiedUser);
    Optional<UserProfile> findByFirebaseUid(String uid);
}
