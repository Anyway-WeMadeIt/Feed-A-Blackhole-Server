package org.example.feedablackhole;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트가 실행되는 동안만 존재하는 MySQL 컨테이너. DB 접속 정보는 Spring이 컨테이너에서 자동으로 가져온다.
 * 버전은 compose.yaml과 같게 유지한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4");
    }

}
