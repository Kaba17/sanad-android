package com.sanad.agent.ssl

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import javax.net.ssl.*

class SanadHttpProxy(
    private val context: Context,
    private val certificateManager: SanadCertificateManager,
    private val interceptCallback: (InterceptedData) -> Unit
) {
    companion object {
        private const val TAG = "SanadHttpProxy"
        const val PROXY_PORT = 8888
        private const val BUFFER_SIZE = 32768
        
        private val DELIVERY_APP_DOMAINS = setOf(
            "hungerstation.com",
            "jahez.net", 
            "toyou.io",
            "mrsool.co",
            "careem.com",
            "api.hungerstation.com",
            "api.jahez.net",
            "api.toyou.io", 
            "api.mrsool.co",
            "api.careem.com"
        )
    }
    
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class InterceptedData(
        val hostname: String,
        val path: String,
        val method: String,
        val requestHeaders: Map<String, String>,
        val responseCode: Int,
        val responseHeaders: Map<String, String>,
        val responseBody: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun start(): Boolean {
        if (isRunning) return true
        
        return try {
            serverSocket = ServerSocket(PROXY_PORT, 100, InetAddress.getByName("127.0.0.1"))
            isRunning = true
            
            scope.launch {
                Log.i(TAG, "Proxy server started on port $PROXY_PORT")
                acceptConnections()
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server", e)
            false
        }
    }
    
    fun stop() {
        isRunning = false
        scope.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        Log.i(TAG, "Proxy server stopped")
    }
    
    private suspend fun acceptConnections() {
        while (isRunning) {
            try {
                val clientSocket = withContext(Dispatchers.IO) {
                    serverSocket?.accept()
                }
                clientSocket?.let { socket ->
                    scope.launch {
                        handleClient(socket)
                    }
                }
            } catch (e: SocketException) {
                if (isRunning) {
                    Log.e(TAG, "Socket exception while accepting", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection", e)
            }
        }
    }
    
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            val clientInput = BufferedInputStream(clientSocket.getInputStream())
            val clientOutput = BufferedOutputStream(clientSocket.getOutputStream())
            
            val requestLine = readLine(clientInput)
            if (requestLine.isNullOrEmpty()) {
                clientSocket.close()
                return
            }
            
            Log.d(TAG, "Request: $requestLine")
            
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                clientSocket.close()
                return
            }
            
            val method = parts[0]
            val target = parts[1]
            
            if (method == "CONNECT") {
                handleConnectRequest(clientSocket, clientInput, clientOutput, target)
            } else {
                handleHttpRequest(clientSocket, clientInput, clientOutput, method, target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) { }
        }
    }
    
    private suspend fun handleConnectRequest(
        clientSocket: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        target: String
    ) {
        val hostPort = target.split(":")
        val hostname = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443
        
        while (true) {
            val line = readLine(clientInput)
            if (line.isNullOrEmpty()) break
        }
        
        val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
        withContext(Dispatchers.IO) {
            clientOutput.write(response.toByteArray())
            clientOutput.flush()
        }
        
        if (isDeliveryAppDomain(hostname)) {
            handleSSLInterception(clientSocket, hostname, port)
        } else {
            tunnelConnection(clientSocket, hostname, port)
        }
    }
    
    private suspend fun handleSSLInterception(
        clientSocket: Socket,
        hostname: String,
        port: Int
    ) {
        val sslContext = certificateManager.createSSLContext(hostname)
        if (sslContext == null) {
            tunnelConnection(clientSocket, hostname, port)
            return
        }
        
        try {
            val sslServerSocketFactory = sslContext.serverSocketFactory as SSLServerSocketFactory
            
            val sslSocket = (sslContext.socketFactory as SSLSocketFactory)
                .createSocket(clientSocket, hostname, port, true) as SSLSocket
            
            sslSocket.useClientMode = false
            sslSocket.startHandshake()
            
            val sslInput = BufferedInputStream(sslSocket.getInputStream())
            val sslOutput = BufferedOutputStream(sslSocket.getOutputStream())
            
            handleDecryptedTraffic(sslSocket, sslInput, sslOutput, hostname, port)
        } catch (e: Exception) {
            Log.e(TAG, "SSL interception failed for $hostname", e)
            tunnelConnection(clientSocket, hostname, port)
        }
    }
    
    private suspend fun handleDecryptedTraffic(
        clientSocket: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        hostname: String,
        port: Int
    ) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            val serverSocket = sslContext.socketFactory.createSocket(hostname, port) as SSLSocket
            serverSocket.startHandshake()
            
            val serverInput = BufferedInputStream(serverSocket.getInputStream())
            val serverOutput = BufferedOutputStream(serverSocket.getOutputStream())
            
            val requestLine = readLine(clientInput)
            if (requestLine.isNullOrEmpty()) {
                serverSocket.close()
                return
            }
            
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            
            val requestHeaders = mutableMapOf<String, String>()
            val requestBuilder = StringBuilder()
            requestBuilder.append(requestLine).append("\r\n")
            
            var contentLength = 0
            while (true) {
                val line = readLine(clientInput) ?: break
                if (line.isEmpty()) {
                    requestBuilder.append("\r\n")
                    break
                }
                requestBuilder.append(line).append("\r\n")
                
                val colonIndex = line.indexOf(":")
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    requestHeaders[key] = value
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }
            
            withContext(Dispatchers.IO) {
                serverOutput.write(requestBuilder.toString().toByteArray())
                
                if (contentLength > 0) {
                    val body = ByteArray(contentLength)
                    clientInput.read(body, 0, contentLength)
                    serverOutput.write(body)
                }
                
                serverOutput.flush()
            }
            
            val responseLine = readLine(serverInput)
            if (responseLine == null) {
                serverSocket.close()
                return
            }
            
            val responseCode = responseLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            
            val responseHeaders = mutableMapOf<String, String>()
            val responseBuilder = StringBuilder()
            responseBuilder.append(responseLine).append("\r\n")
            
            var responseContentLength = -1
            var isChunked = false
            
            while (true) {
                val line = readLine(serverInput) ?: break
                if (line.isEmpty()) {
                    responseBuilder.append("\r\n")
                    break
                }
                responseBuilder.append(line).append("\r\n")
                
                val colonIndex = line.indexOf(":")
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    responseHeaders[key] = value
                    
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        responseContentLength = value.toIntOrNull() ?: 0
                    }
                    if (key.equals("Transfer-Encoding", ignoreCase = true) && value.contains("chunked")) {
                        isChunked = true
                    }
                }
            }
            
            val responseBodyBytes = ByteArrayOutputStream()
            
            if (isChunked) {
                readChunkedBody(serverInput, responseBodyBytes)
            } else if (responseContentLength > 0) {
                val buffer = ByteArray(responseContentLength)
                var totalRead = 0
                while (totalRead < responseContentLength) {
                    val read = withContext(Dispatchers.IO) {
                        serverInput.read(buffer, totalRead, responseContentLength - totalRead)
                    }
                    if (read == -1) break
                    totalRead += read
                }
                responseBodyBytes.write(buffer, 0, totalRead)
            }
            
            val responseBody = responseBodyBytes.toString(StandardCharsets.UTF_8.name())
            
            withContext(Dispatchers.IO) {
                clientOutput.write(responseBuilder.toString().toByteArray())
                clientOutput.write(responseBodyBytes.toByteArray())
                clientOutput.flush()
            }
            
            if (responseBody.isNotEmpty() && 
                (responseHeaders["Content-Type"]?.contains("json") == true ||
                 responseBody.trimStart().startsWith("{"))) {
                
                val intercepted = InterceptedData(
                    hostname = hostname,
                    path = path,
                    method = method,
                    requestHeaders = requestHeaders,
                    responseCode = responseCode,
                    responseHeaders = responseHeaders,
                    responseBody = responseBody
                )
                
                interceptCallback(intercepted)
                Log.d(TAG, "Intercepted: $method $hostname$path (${responseBody.length} bytes)")
            }
            
            serverSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error in decrypted traffic handling", e)
        }
    }
    
    private suspend fun readChunkedBody(input: BufferedInputStream, output: ByteArrayOutputStream) {
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = try {
                sizeLine.trim().toInt(16)
            } catch (e: Exception) {
                0
            }
            
            if (size == 0) {
                readLine(input)
                break
            }
            
            val chunk = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val read = withContext(Dispatchers.IO) {
                    input.read(chunk, totalRead, size - totalRead)
                }
                if (read == -1) break
                totalRead += read
            }
            output.write(chunk, 0, totalRead)
            
            readLine(input)
        }
    }
    
    private suspend fun handleHttpRequest(
        clientSocket: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        method: String,
        url: String
    ) {
        try {
            val uri = URI(url)
            val hostname = uri.host
            val port = if (uri.port > 0) uri.port else 80
            val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + 
                       (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
            
            val serverSocket = Socket(hostname, port)
            val serverInput = BufferedInputStream(serverSocket.getInputStream())
            val serverOutput = BufferedOutputStream(serverSocket.getOutputStream())
            
            val requestBuilder = StringBuilder()
            requestBuilder.append("$method $path HTTP/1.1\r\n")
            
            while (true) {
                val line = readLine(clientInput) ?: break
                if (line.isEmpty()) {
                    requestBuilder.append("\r\n")
                    break
                }
                requestBuilder.append(line).append("\r\n")
            }
            
            withContext(Dispatchers.IO) {
                serverOutput.write(requestBuilder.toString().toByteArray())
                serverOutput.flush()
            }
            
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = withContext(Dispatchers.IO) {
                    serverInput.read(buffer)
                }
                if (read == -1) break
                withContext(Dispatchers.IO) {
                    clientOutput.write(buffer, 0, read)
                    clientOutput.flush()
                }
            }
            
            serverSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTP request", e)
        }
    }
    
    private suspend fun tunnelConnection(clientSocket: Socket, hostname: String, port: Int) {
        try {
            val serverSocket = Socket(hostname, port)
            
            val job1 = scope.launch {
                tunnel(clientSocket.getInputStream(), serverSocket.getOutputStream())
            }
            val job2 = scope.launch {
                tunnel(serverSocket.getInputStream(), clientSocket.getOutputStream())
            }
            
            job1.join()
            job2.join()
            
            serverSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error to $hostname:$port", e)
        }
    }
    
    private suspend fun tunnel(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = withContext(Dispatchers.IO) {
                    input.read(buffer)
                }
                if (read == -1) break
                withContext(Dispatchers.IO) {
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // Connection closed
        }
    }
    
    private suspend fun readLine(input: BufferedInputStream): String? {
        return withContext(Dispatchers.IO) {
            val line = StringBuilder()
            var prevByte = -1
            while (true) {
                val b = input.read()
                if (b == -1) {
                    return@withContext if (line.isEmpty()) null else line.toString()
                }
                if (b == '\n'.code) {
                    if (prevByte == '\r'.code) {
                        line.deleteCharAt(line.length - 1)
                    }
                    break
                }
                line.append(b.toChar())
                prevByte = b
            }
            line.toString()
        }
    }
    
    private fun isDeliveryAppDomain(hostname: String): Boolean {
        return DELIVERY_APP_DOMAINS.any { domain ->
            hostname == domain || hostname.endsWith(".$domain")
        }
    }
}
