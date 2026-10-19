package org.example.feedablackhole.account.service;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정을 만드는 유일한 경로. 계정과 처음 상태의 진행 상태를 한 트랜잭션으로 만들어,
 * 진행 상태가 없는 계정이 생기지 않게 한다(로그인 수단이 늘어도 이 메서드를 쓴다).
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final PlayerProgressRepository progressRepository;

    @Transactional
    public Account create() {
        Account account = accountRepository.save(Account.create());
        progressRepository.save(PlayerProgress.initial(account));
        return account;
    }

}
