package org.rainbowhunter.adminpanel.core.auth

import de.mkammerer.argon2.Argon2Factory
import kotlinx.serialization.Serializable

class Argon2Hasher(
    private val iterations: Int = 2,
    private val memoryKb: Int = 65536,
    private val parallelism: Int = 1,
) {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(password: String): String =
        argon2.hash(iterations, memoryKb, parallelism, password.toCharArray())

    fun verify(hash: String, password: String): Boolean =
        argon2.verify(hash, password.toCharArray())
}

data class UserPrincipal(val user: UserRecord)

@Serializable
data class ErrorResponse(val error: String)
