package app.termora.masterpassword

import kotlinx.serialization.Serializable

/**
 * aes-secret 的原子载体：DatabaseSecret 的 password + salt 打包，整体加解密，
 * 避免 salt 单独损坏导致 db 彻底打不开。
 */
@Serializable
data class DbSecret(val password: String, val salt: String)
