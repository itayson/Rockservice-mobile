package org.rockservice.mobile

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.rockservice.core.usb.adb.AdbHandshakeIdentity
import org.rockservice.core.usb.adb.AdbRsaAuth

/** App-private persistent RSA identity used only for explicit ADB authorization handshakes. */
internal class AdbAppIdentityStore(
    context: Context,
) {
    private val identityDirectory = File(context.applicationContext.noBackupFilesDir, IDENTITY_DIRECTORY_NAME)
    private val privateKeyFile = File(identityDirectory, PRIVATE_KEY_FILE_NAME)
    private val publicKeyFile = File(identityDirectory, PUBLIC_KEY_FILE_NAME)
    private val lock = Any()

    /** Loads the existing identity or creates one atomically when no identity exists yet. */
    fun loadOrCreate(): AdbHandshakeIdentity = synchronized(lock) {
        when {
            privateKeyFile.exists() && publicKeyFile.exists() -> loadExisting()
            !privateKeyFile.exists() && !publicKeyFile.exists() -> generateAndStore()
            else -> throw IOException(
                "A identidade ADB privada esta incompleta. Limpe os dados do aplicativo para gerar uma nova identidade segura.",
            )
        }
    }

    private fun loadExisting(): AdbHandshakeIdentity {
        val privateBytes = privateKeyFile.readBytesBounded(MAXIMUM_PRIVATE_KEY_BYTES, "chave privada ADB")
        val publicBytes = publicKeyFile.readBytesBounded(MAXIMUM_PUBLIC_KEY_BYTES, "chave publica ADB")
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)) as? RSAPrivateKey
            ?: throw IOException("A chave privada ADB armazenada nao e RSA.")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicBytes)) as? RSAPublicKey
            ?: throw IOException("A chave publica ADB armazenada nao e RSA.")
        validatePair(privateKey, publicKey)
        return StoredAdbIdentity(privateKey, publicKey)
    }

    private fun generateAndStore(): AdbHandshakeIdentity {
        check(identityDirectory.exists() || identityDirectory.mkdirs()) {
            "Nao foi possivel criar o diretorio privado da identidade ADB."
        }
        val pair = KeyPairGenerator.getInstance("RSA").run {
            initialize(AdbRsaAuth.RSA_BITS)
            generateKeyPair()
        }
        val privateKey = pair.private as RSAPrivateKey
        val publicKey = pair.public as RSAPublicKey
        validatePair(privateKey, publicKey)

        writePrivateAtomically(privateKeyFile, privateKey.encoded)
        try {
            writePrivateAtomically(publicKeyFile, publicKey.encoded)
        } catch (error: Throwable) {
            privateKeyFile.delete()
            throw error
        }
        return StoredAdbIdentity(privateKey, publicKey)
    }

    private fun validatePair(privateKey: RSAPrivateKey, publicKey: RSAPublicKey) {
        require(privateKey.modulus == publicKey.modulus) {
            "As chaves ADB armazenadas nao pertencem ao mesmo par RSA."
        }
        require(privateKey.modulus.bitLength() == AdbRsaAuth.RSA_BITS) {
            "A identidade ADB armazenada nao usa RSA ${AdbRsaAuth.RSA_BITS}."
        }
        AdbRsaAuth.encodeAndroidPublicKey(publicKey)
    }

    private fun writePrivateAtomically(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Material de chave ADB nao pode ser vazio." }
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Nao foi possivel limpar arquivo temporario da identidade ADB.")
        }
        temporary.outputStream().buffered().use { output ->
            output.write(bytes)
            output.flush()
        }
        restrictToAppOwner(temporary)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Nao foi possivel concluir a gravacao atomica da identidade ADB.")
        }
        restrictToAppOwner(target)
    }

    private fun restrictToAppOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        check(file.setReadable(true, true)) { "Nao foi possivel restringir leitura da identidade ADB." }
        check(file.setWritable(true, true)) { "Nao foi possivel restringir escrita da identidade ADB." }
    }

    private fun File.readBytesBounded(maximumBytes: Int, label: String): ByteArray {
        val length = length()
        require(length in 1..maximumBytes.toLong()) {
            "$label armazenada possui tamanho invalido: $length bytes."
        }
        return inputStream().use { input ->
            val bytes = input.readBytes()
            require(bytes.size.toLong() == length && bytes.size <= maximumBytes) {
                "$label mudou durante a leitura ou excedeu o limite configurado."
            }
            bytes
        }
    }

    private class StoredAdbIdentity(
        private val privateKey: RSAPrivateKey,
        private val publicKey: RSAPublicKey,
    ) : AdbHandshakeIdentity {
        override fun signToken(token: ByteArray): ByteArray = AdbRsaAuth.signToken(privateKey, token)

        override fun publicKeyRecord(): String =
            AdbRsaAuth.encodePublicKeyRecord(publicKey, PUBLIC_KEY_COMMENT)
    }

    private companion object {
        const val IDENTITY_DIRECTORY_NAME = "adb-identity"
        const val PRIVATE_KEY_FILE_NAME = "adbkey.pk8"
        const val PUBLIC_KEY_FILE_NAME = "adbkey.pub.der"
        const val PUBLIC_KEY_COMMENT = "rockservice@android"
        const val MAXIMUM_PRIVATE_KEY_BYTES = 16 * 1024
        const val MAXIMUM_PUBLIC_KEY_BYTES = 8 * 1024
    }
}
