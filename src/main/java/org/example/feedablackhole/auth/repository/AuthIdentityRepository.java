package org.example.feedablackhole.auth.repository;

import java.util.Optional;

import org.example.feedablackhole.auth.entity.AuthIdentity;
import org.example.feedablackhole.auth.entity.AuthIdentityType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, Long> {

    Optional<AuthIdentity> findByTypeAndIdentifier(AuthIdentityType type, String identifier);

}
