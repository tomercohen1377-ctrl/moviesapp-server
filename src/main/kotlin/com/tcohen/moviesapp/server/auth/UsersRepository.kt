package com.tcohen.moviesapp.server.auth

import org.springframework.data.jpa.repository.JpaRepository

interface UsersRepository : JpaRepository<User, String> {
    fun existsByUserId(userId: String): Boolean
}
