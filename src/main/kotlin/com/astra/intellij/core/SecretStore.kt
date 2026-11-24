package com.astra.intellij.core

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps request credentials in the IDE credential store (the OS keychain on macOS and Windows)
 * instead of the plain-text workspace file.
 */
object SecretStore : SecretWriter, SecretReader {
    private val cache = ConcurrentHashMap<String, String>()

    override fun write(ref: String, value: String): Boolean {
        if (cache[ref] == value) return true
        val stored = runCatching {
            PasswordSafe.instance.setPassword(attributes(ref), value)
            true
        }.getOrDefault(false)
        if (stored) cache[ref] = value
        return stored
    }

    override fun read(ref: String): String? {
        cache[ref]?.let { return it }
        return runCatching { PasswordSafe.instance.getPassword(attributes(ref)) }
            .getOrNull()
            ?.also { cache[ref] = it }
    }

    fun forget(ref: String) {
        cache.remove(ref)
        runCatching { PasswordSafe.instance.setPassword(attributes(ref), null) }
    }

    private fun attributes(ref: String) = CredentialAttributes("Astra/$ref")
}
