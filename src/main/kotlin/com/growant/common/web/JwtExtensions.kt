package com.growant.common.web

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.springframework.security.oauth2.jwt.Jwt

/** JWT sub → userId 추출 단일 정의(스펙 DRY §3-4) — 컨트롤러 공용. sub는 AuthService가 DB id로 발급한다. */
val Jwt.userId: Long
    get() = subject.toLongOrNull()
        ?: throw BusinessException(ErrorCode.UNAUTHENTICATED) // 비숫자 sub(비정상 토큰) — 500 대신 401
