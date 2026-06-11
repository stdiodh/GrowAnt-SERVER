package com.growant.auth.dto

data class LoginRequestDto(val provider: String, val nickname: String)

data class UserDto(val id: Long, val nickname: String, val provider: String)

data class AuthResponseDto(val token: String, val user: UserDto)
