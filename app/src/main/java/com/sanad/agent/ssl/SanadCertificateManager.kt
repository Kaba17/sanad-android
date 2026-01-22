package com.sanad.agent.ssl

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

class SanadCertificateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SanadCertManager"
        private const val CA_ALIAS = "sanad_root_ca"
        private const val KEYSTORE_PASSWORD = "sanad_secure_2024"
        private const val KEYSTORE_FILENAME = "sanad_keystore.bks"
        private const val CA_CERT_FILENAME = "sanad_ca.crt"
        
        init {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
    
    private var keyStore: KeyStore? = null
    private var caKeyPair: KeyPair? = null
    private var caCertificate: X509Certificate? = null
    
    // Thread-safe cache for generated host certificates
    private val generatedCerts = java.util.concurrent.ConcurrentHashMap<String, Pair<X509Certificate, PrivateKey>>()
    
    // Lock for certificate generation to prevent duplicate generation
    private val certGenerationLock = Any()
    
    fun initialize(): Boolean {
        return try {
            loadOrCreateKeyStore()
            loadOrCreateCACertificate()
            Log.i(TAG, "Certificate manager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize certificate manager", e)
            false
        }
    }
    
    private fun loadOrCreateKeyStore() {
        keyStore = KeyStore.getInstance("BKS")
        val keystoreFile = File(context.filesDir, KEYSTORE_FILENAME)
        
        if (keystoreFile.exists()) {
            FileInputStream(keystoreFile).use { fis ->
                keyStore?.load(fis, KEYSTORE_PASSWORD.toCharArray())
            }
            Log.i(TAG, "Loaded existing keystore")
        } else {
            keyStore?.load(null, KEYSTORE_PASSWORD.toCharArray())
            Log.i(TAG, "Created new keystore")
        }
    }
    
    private fun loadOrCreateCACertificate() {
        if (keyStore?.containsAlias(CA_ALIAS) == true) {
            caCertificate = keyStore?.getCertificate(CA_ALIAS) as? X509Certificate
            caKeyPair = KeyPair(
                caCertificate?.publicKey,
                keyStore?.getKey(CA_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as? PrivateKey
            )
            Log.i(TAG, "Loaded existing CA certificate")
        } else {
            generateCACertificate()
            saveCACertificate()
            Log.i(TAG, "Generated new CA certificate")
        }
    }
    
    private fun generateCACertificate() {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        caKeyPair = keyPairGenerator.generateKeyPair()
        
        val issuer = X500Name("CN=Sanad Root CA, O=Sanad Consumer Rights, C=SA")
        val serial = BigInteger(160, SecureRandom())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            caKeyPair!!.public
        )
        
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )
        
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(caKeyPair!!.private)
        
        val certHolder: X509CertificateHolder = certBuilder.build(signer)
        caCertificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder)
    }
    
    private fun saveCACertificate() {
        keyStore?.setKeyEntry(
            CA_ALIAS,
            caKeyPair!!.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(caCertificate)
        )
        
        val keystoreFile = File(context.filesDir, KEYSTORE_FILENAME)
        FileOutputStream(keystoreFile).use { fos ->
            keyStore?.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }
    }
    
    fun generateHostCertificate(hostname: String): Pair<X509Certificate, PrivateKey>? {
        // Check cache first (thread-safe read)
        generatedCerts[hostname]?.let { return it }
        
        // Synchronize certificate generation to prevent duplicate work
        return synchronized(certGenerationLock) {
            // Double-check after acquiring lock
            generatedCerts[hostname]?.let { return@synchronized it }
            
            try {
                val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
                keyPairGenerator.initialize(2048, SecureRandom())
                val hostKeyPair = keyPairGenerator.generateKeyPair()
                
                val issuer = X500Name("CN=Sanad Root CA, O=Sanad Consumer Rights, C=SA")
                val subject = X500Name("CN=$hostname, O=Sanad Intercepted, C=SA")
                val serial = BigInteger(160, SecureRandom())
                val notBefore = Date()
                val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
                
                val certBuilder = JcaX509v3CertificateBuilder(
                    issuer,
                    serial,
                    notBefore,
                    notAfter,
                    subject,
                    hostKeyPair.public
                )
                
                certBuilder.addExtension(
                    Extension.basicConstraints,
                    true,
                    BasicConstraints(false)
                )
                
                val sanBuilder = org.bouncycastle.asn1.x509.GeneralNamesBuilder()
                sanBuilder.addName(org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, hostname
                ))
                certBuilder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    sanBuilder.build()
                )
                
                val signer = JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(caKeyPair!!.private)
                
                val certHolder = certBuilder.build(signer)
                val hostCert = JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(certHolder)
                
                val result = Pair(hostCert, hostKeyPair.private)
                generatedCerts[hostname] = result
                
                Log.d(TAG, "Generated certificate for: $hostname")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate certificate for $hostname", e)
                null
            }
        }
    }
    
    fun createSSLContext(hostname: String): SSLContext? {
        val certPair = generateHostCertificate(hostname) ?: return null
        
        return try {
            val ks = KeyStore.getInstance("BKS")
            ks.load(null, null)
            ks.setKeyEntry(
                "host",
                certPair.second,
                KEYSTORE_PASSWORD.toCharArray(),
                arrayOf(certPair.first, caCertificate)
            )
            
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())
            
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
            sslContext
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSL context for $hostname", e)
            null
        }
    }
    
    fun exportCACertificate(): File? {
        return try {
            val certFile = File(context.getExternalFilesDir(null), CA_CERT_FILENAME)
            FileOutputStream(certFile).use { fos ->
                fos.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
                fos.write(Base64.getEncoder().encodeToString(caCertificate?.encoded).toByteArray())
                fos.write("\n-----END CERTIFICATE-----\n".toByteArray())
            }
            Log.i(TAG, "CA certificate exported to: ${certFile.absolutePath}")
            certFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CA certificate", e)
            null
        }
    }
    
    fun getCACertificateFile(): File {
        return File(context.getExternalFilesDir(null), CA_CERT_FILENAME)
    }
    
    fun isCACertificateExported(): Boolean {
        return getCACertificateFile().exists()
    }
}
