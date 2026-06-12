package com.growant.auth

/** 데모 사용자 — (provider, nickname) 조합이 신원(비밀번호 없음). 영속성 슬라이스에서 DB로 이관. */
data class User(val id: Long, val nickname: String, val provider: String)
