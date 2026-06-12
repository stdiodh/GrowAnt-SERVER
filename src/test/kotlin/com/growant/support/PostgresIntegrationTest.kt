package com.growant.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Testcontainers 공용 베이스(스펙 DRY §3-5) — 싱글턴 컨테이너를 모든 IT가 공유한다.
 * @Container 생명주기 대신 수동 start(JVM당 1회): IT 클래스마다 컨테이너 재기동을 막는다.
 * 컨테이너 정리는 testcontainers의 Ryuk이 JVM 종료 시 수행한다.
 * 데이터는 IT 간 공유되므로 각 테스트는 고유 닉네임으로 자체 사용자를 만든다(롤백에 기대지 않는 설계).
 */
@SpringBootTest
abstract class PostgresIntegrationTest {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    }
}
