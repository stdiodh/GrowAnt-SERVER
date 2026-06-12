package com.growant.auth

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** in-memory find-or-create — 같은 (provider, nickname) 재로그인이면 같은 User(멱등). 재시작 시 초기화. */
@Component
class UserStore {
    private val seq = AtomicLong(0)
    private val users = ConcurrentHashMap<String, User>()

    fun findOrCreate(provider: String, nickname: String): User =
        users.computeIfAbsent("$provider:$nickname") {
            User(seq.incrementAndGet(), nickname, provider)
        }
}
