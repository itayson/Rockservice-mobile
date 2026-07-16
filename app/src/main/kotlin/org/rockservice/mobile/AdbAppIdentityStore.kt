package org.rockservice.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import org.rockservice.core.usb.adb.AdbHandshakeIdentity
import org.rockservice.core.usb.adb.AdbRsaAuth

/** App-private persistent RSA identity used only for explicit ADB authorization handshakes. */
internal class AdbAppIdentityStore(
    context: Context,
) {
    private val identityDirectory = File(context.applicationContext.noBackupFilesDir, IDENTITY_DIRECTORY_NAME)
    private val privateKeyFile = File(identityDirectory, PRIVATE_KEY_FILE_NAME)
    private val publicKeyFile = File(identityDirectory, PUBLIC_KEY_FILE_NAME)
    private val integrityMacFile = File(identityDirectory, INTEGRITY_MAC_FILE_NAME)

    /** Loads the existing identity or creates one atomically when no identity exists yet. */
    fun loadOrCreate(): AdbHandshakeIdentity = synchronized(IDENTITY_LOCK) {
        val files = listOf(privateKeyFile, publicKeyFile, integrityMacFile)
        when {
            files.all(File::exists) -> loadExisting()
            files.none(File::exists) -> generateAndStore()
            else -> throw IOException(
                "A identidade ADB privada esta incompleta ou sem prova de integridade. " +
                    "Limpe os dados do aplicativo para gerar uma nova identidade segura.",
            )
        }
    }

    private fun loadExisting(): AdbHandshakeIdentity {
        val privateBytes = privateKeyFile.readBytesBounded(MAXIMUM_PRIVATE_KEY_BYTES, "chave privada ADB")
        val publicBytes = publicKeyFile.readBytesBounded(MAXIMUM_PUBLIC_KEY_BYTES, "chave publica ADB")
        val storedMac = integrityMacFile.readBytesBounded(INTEGRITY_MAC_BYTES, "MAC de integridade ADB")
        require(storedMac.size == INTEGRITY_MAC_BYTES) {
            "MAC de integridade ADB possui ${storedMac.size} bytes; esperado: $INTEGRITY_MAC_BYTES."
        }

        val expectedMac = computeIntegrityMac(privateBytes, publicBytes)
        require(MessageDigest.isEqual(storedMac, expectedMac)) {
            "A integridade da identidade ADB armazenada nao pode ser validada. " +
                "Os arquivos podem ter sido alterados; limpe os dados do aplicativo antes de continuar."
        }

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

        val privateBytes = requireNotNull(privateKey.encoded) { "A chave privada ADB nao possui codificacao PKCS#8." }
        val publicBytes = requireNotNull(publicKey.encoded) { "A chave publica ADB nao possui codificacao X.509." }
        val integrityMac = computeIntegrityMac(privateBytes, publicBytes)

        try {
            writePrivateAtomically(privateKeyFile, privateBytes)
            writePrivateAtomically(publicKeyFile, publicBytes)
            writePrivateAtomically(integrityMacFile, integrityMac)
        } catch (error: Throwable) {
            rollbackIdentityFiles(error)
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

    private fun computeIntegrityMac(privateBytes: ByteArray, publicBytes: ByteArray): ByteArray {
        val key = loadOrCreateIntegrityKey()
        val canonical = ByteBuffer.allocate(8 + privateBytes.size + publicBytes.size)
            .putInt(privateBytes.size)
            .put(privateBytes)
            .putInt(publicBytes.size)
            .put(publicBytes)
            .array()
        return Mac.getInstance(INTEGRITY_MAC_ALGORITHM).run {
            init(key)
            doFinal(canonical)
        }
    }

    private fun loadOrCreateIntegrityKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(INTEGRITY_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    INTEGRITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun writePrivateAtomically(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Material da identidade ADB nao pode ser vazio." }
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(bytes)
                output.flush()
            }
            restrictToAppOwner(temporary)
            if (!temporary.renameTo(target)) {
                throw IOException("Nao foi possivel concluir a gravacao atomica de ${target.name}.")
            }
            restrictToAppOwner(target)
        } catch (error: Throwable) {
            if (temporary.exists() && !temporary.delete()) {
                error.addSuppressed(IOException("Nao foi possivel remover o temporario ${temporary.name}."))
            }
            throw error
        }
    }

    private fun restrictToAppOwner(file: File) {
        val globalReadRemoved = file.setReadable(false, false)
        val globalWriteRemoved = file.setWritable(false, false)
        val globalExecuteRemoved = file.setExecutable(false, false)
        if (!globalReadRemoved || !globalWriteRemoved || !globalExecuteRemoved) {
            val cleanupSucceeded = !file.exists() || file.delete()
            throw IOException(
                "Nao foi possivel remover permissoes globais de ${file.name}; " +
                    "arquivo removido=$cleanupSucceeded.",
            )
        }
        if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
            val cleanupSucceeded = !file.exists() || file.delete()
            throw IOException(
                "Nao foi possivel restringir ${file.name} ao proprietario do aplicativo; " +
                    "arquivo removido=$cleanupSucceeded.",
            )
        }
    }

    private fun rollbackIdentityFiles(original: Throwable) {
        listOf(privateKeyFile, publicKeyFile, integrityMacFile).forEach { file ->
            if (file.exists() && !file.delete()) {
                original.addSuppressed(
                    IOException("Falha critica ao remover ${file.name} durante rollback da identidade ADB."),
                )
            }
        }
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
        val IDENTITY_LOCK = Any()

        const val IDENTITY_DIRECTORY_NAME = "adb-identity"
        const val PRIVATE_KEY_FILE_NAME = "adbkey.pk8"
        const val PUBLIC_KEY_FILE_NAME = "adbkey.pub.der"
        const val INTEGRITY_MAC_FILE_NAME = "adbkey.integrity.mac"
        const val PUBLIC_KEY_COMMENT = "rockservice@android"
        const val MAXIMUM_PRIVATE_KEY_BYTES = 16 * 1024
        const val MAXIMUM_PUBLIC_KEY_BYTES = 8 * 1024
        const val INTEGRITY_MAC_BYTES = 32
        const val INTEGRITY_MAC_ALGORITHM = "HmacSHA256"
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val INTEGRITY_KEY_ALIAS = "rockservice.adb.identity.integrity.v1"
    }
}
