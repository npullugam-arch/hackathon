package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTests {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserProfileRepository> provider = mock(ObjectProvider.class);
    private final UserProfileRepository repository = mock(UserProfileRepository.class);
    private final UserProfileService service = new UserProfileService(provider);

    @Test
    void readsPersistedProfileRatherThanFallingBackToIdentityProvider() {
        when(provider.getIfAvailable()).thenReturn(repository);
        var stored = new UserProfile("uid", "stored@example.com", "Stored name", null, true,
                "google.com", 1, 2, Instant.EPOCH, Instant.EPOCH);
        when(repository.findByFirebaseUid("uid")).thenReturn(Optional.of(stored));
        assertEquals("Stored name", service.findByUid("uid").orElseThrow().name());
    }

    @Test
    void databaseExceptionsAreTranslatedWithoutLeakingConnectionDetails() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(repository.findByFirebaseUid("uid")).thenThrow(new DataAccessResourceFailureException("secret connection details"));
        var error = assertThrows(ProfileStorageUnavailableException.class, () -> service.findByUid("uid"));
        assertFalse(error.toString().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void disabledStorageNeverSilentlyAcceptsAProfileWrite() {
        var user = new AuthUser("uid", "Name", "email@example.com", null, true, 1, 2, "google.com");
        assertThrows(ProfileStorageUnavailableException.class, () -> service.synchronize(user));
        verifyNoInteractions(repository);
    }
}
