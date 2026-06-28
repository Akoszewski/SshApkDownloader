package com.example.sshapkdownloader

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey

object SshKeyGenerator {
    fun generate(): GeneratedSshKeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()

        val privateKey = keyPair.private as RSAPrivateCrtKey
        val publicKey = keyPair.public as RSAPublicKey

        return GeneratedSshKeyPair(
            privateKeyPem = privateKey.toPkcs1Pem(),
            publicKeyOpenSsh = publicKey.toOpenSshPublicKey()
        )
    }

    private fun RSAPrivateCrtKey.toPkcs1Pem(): String {
        val der = derSequence(
            derInteger(BigInteger.ZERO),
            derInteger(modulus),
            derInteger(publicExponent),
            derInteger(privateExponent),
            derInteger(primeP),
            derInteger(primeQ),
            derInteger(primeExponentP),
            derInteger(primeExponentQ),
            derInteger(crtCoefficient)
        )
        val encoded = Base64.encodeToString(der, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
        return "-----BEGIN RSA PRIVATE KEY-----\n$encoded\n-----END RSA PRIVATE KEY-----"
    }

    private fun RSAPublicKey.toOpenSshPublicKey(): String {
        val blob = ByteArrayOutputStream().apply {
            writeSshString("ssh-rsa".toByteArray(Charsets.US_ASCII))
            writeSshMpint(publicExponent)
            writeSshMpint(modulus)
        }.toByteArray()
        val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
        return "ssh-rsa $encoded ssh-apk-downloader"
    }

    private fun derSequence(vararg values: ByteArray): ByteArray {
        val body = values.fold(ByteArray(0)) { acc, next -> acc + next }
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }

    private fun derLength(length: Int): ByteArray {
        if (length < 128) {
            return byteArrayOf(length.toByte())
        }

        var value = length
        val bytes = ArrayList<Byte>()
        while (value > 0) {
            bytes.add(0, (value and 0xff).toByte())
            value = value ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.writeSshString(value: ByteArray) {
        writeUInt32(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeSshMpint(value: BigInteger) {
        writeSshString(value.toByteArray())
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Int) {
        write(byteArrayOf(
            ((value ushr 24) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            (value and 0xff).toByte()
        ))
    }
}
