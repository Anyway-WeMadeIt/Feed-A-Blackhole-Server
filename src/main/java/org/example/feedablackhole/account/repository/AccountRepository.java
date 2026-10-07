package org.example.feedablackhole.account.repository;

import org.example.feedablackhole.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
