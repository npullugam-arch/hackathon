package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.supabase.enabled", havingValue = "true")
public class JdbcUserProfileRepository implements UserProfileRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<UserProfile> MAPPER = (rs, row) -> new UserProfile(
            rs.getString("firebase_uid"), rs.getString("email"), rs.getString("name"),
            rs.getString("photo_url"), rs.getBoolean("email_verified"), rs.getString("provider"),
            rs.getLong("firebase_created_at"), rs.getTimestamp("last_login_at").getTime(),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getString("phone_number"));

    public JdbcUserProfileRepository(JdbcTemplate supabaseJdbcTemplate) { this.jdbc = supabaseJdbcTemplate; }

    @Override
    public UserProfile upsert(AuthUser user) {
        // One atomic PostgreSQL statement prevents duplicates even during concurrent first logins.
        return jdbc.queryForObject("""
                INSERT INTO public.users
                    (firebase_uid, email, name, photo_url, email_verified, provider,
                     firebase_created_at, last_login_at, phone_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (firebase_uid) DO UPDATE SET
                    email = EXCLUDED.email,
                    name = EXCLUDED.name,
                    photo_url = EXCLUDED.photo_url,
                    email_verified = EXCLUDED.email_verified,
                    provider = EXCLUDED.provider,
                    phone_number = COALESCE(EXCLUDED.phone_number, users.phone_number),
                    last_login_at = GREATEST(users.last_login_at, EXCLUDED.last_login_at),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING *
                """, MAPPER, user.uid(), user.email(), user.name(), user.photoUrl(), user.emailVerified(),
                user.provider(), user.createdAt(), new java.sql.Timestamp(user.lastSignInAt()), user.phoneNumber());
    }

    @Override
    public Optional<UserProfile> findByFirebaseUid(String uid) {
        return jdbc.query("SELECT * FROM public.users WHERE firebase_uid = ?", MAPPER, uid)
                .stream().findFirst();
    }
}
