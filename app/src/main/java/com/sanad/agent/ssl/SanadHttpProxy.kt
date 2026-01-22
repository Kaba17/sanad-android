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
        private const val SOCKET_TIMEOUT = 30000
        
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
    @Volatile
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
            serverSocket?.soTimeout = 0 // Block indefinitely on accept
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
                    socket.soTimeout = SOCKET_TIMEOUT
                    scope.launch {
                        try {
                            handleClient(socket)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling client", e)
                        } finally {
                            socket.closeQuietly()
                        }
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
        val clientInput = BufferedInputStream(clientSocket.getInputStream())
        val clientOutput = BufferedOutputStream(clientSocket.getOutputStream())
        
        val requestLine = readLine(clientInput)
        if (requestLine.isNullOrEmpty()) {
            return
        }
        
        Log.d(TAG, "Request: $requestLine")
        
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            return
        }
        
        val method = parts[0]
        val target = parts[1]
        
        if (method == "CONNECT") {
            handleConnectRequest(clientSocket, clientInput, clientOutput, target)
        } else {
            handleHttpRequest(clientSocket, clientInput, clientOutput, method, target, requestLine)
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
        
        // Read remaining headers
        while (true) {
            val line = readLine(clientInput)
            if (line.isNullOrEmpty()) break
        }
        
        // Send 200 Connection Established
        val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
        withContext(Dispatchers.IO) {
            clientOutput.write(response.toByteArray())
            clientOutput.flush()
        }
        
        if (isDeliveryAppDomain(hostname)) {
            performSSLInterception(clientSocket, hostname, port)
        } else {
            // For non-delivery apps, just tunnel the connection
            tunnelConnection(clientSocket, hostname, port)
        }
    }
    
    /**
     * Performs proper SSL MITM interception:
     * 1. Accept TLS from client using our generated certificate (server mode)
     * 2. Establish TLS connection to real origin server (client mode)
     * 3. Forward requests and responses bidirectionally while intercepting
     */
    private suspend fun performSSLInterception(
        clientSocket: Socket,
        hostname: String,
        port: Int
    ) {
        val serverSSLContext = certificateManager.createSSLContext(hostname)
        if (serverSSLContext == null) {
            Log.w(TAG, "Failed to create SSL context for $hostname, falling back to tunnel")
            tunnelConnection(clientSocket, hostname, port)
            return
        }
        
        var clientSSLSocket: SSLSocket? = null
        var serverSSLSocket: SSLSocket? = null
        
        try {
            // Step 1: Create SSL socket to accept TLS from client (act as server)
            // The key is to wrap the existing client socket with SSL in server mode
            val ssf = serverSSLContext.socketFactory as SSLSocketFactory
            clientSSLSocket = ssf.createSocket(
                clientSocket,
                clientSocket.inetAddress.hostAddress,
                clientSocket.port,
                false // Don't auto-close underlying socket
            ) as SSLSocket
            
            clientSSLSocket.useClientMode = false // We are the server
            clientSSLSocket.soTimeout = SOCKET_TIMEOUT
            
            // Perform TLS handshake with client
            withContext(Dispatchers.IO) {
                clientSSLSocket.startHandshake()
            }
            Log.d(TAG, "TLS handshake with client completed for $hostname")
            
            // Step 2: Create SSL connection to actual origin server (act as client)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            
            val clientSSLContext = SSLContext.getInstance("TLS")
            clientSSLContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            serverSSLSocket = withContext(Dispatchers.IO) {
                clientSSLContext.socketFactory.createSocket(hostname, port) as SSLSocket
            }
            serverSSLSocket.soTimeout = SOCKET_TIMEOUT
            
            // Set SNI (Server Name Indication) for virtual hosts
            val sslParams = serverSSLSocket.sslParameters
            sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(hostname))
            serverSSLSocket.sslParameters = sslParams
            
            withContext(Dispatchers.IO) {
                serverSSLSocket.startHandshake()
            }
            Log.d(TAG, "TLS handshake with origin server $hostname:$port completed")
            
            // Step 3: Forward traffic bidirectionally with interception
            handleInterceptedTraffic(
                clientSSLSocket,
                serverSSLSocket,
                hostname
            )
            
        } catch (e: SSLHandshakeException) {
            Log.e(TAG, "SSL handshake failed for $hostname: ${e.message}")
            // Fall back to tunneling without interception
            tunnelConnection(clientSocket, hostname, port)
        } catch (e: Exception) {
            Log.e(TAG, "SSL interception error for $hostname", e)
        } finally {
            clientSSLSocket?.closeQuietly()
            serverSSLSocket?.closeQuietly()
        }
    }
    
    /**
     * Handle the decrypted HTTP traffic between client and server
     */
    private suspend fun handleInterceptedTraffic(
        clientSocket: SSLSocket,
        serverSocket: SSLSocket,
        hostname: String
    ) {
        val clientInput = BufferedInputStream(clientSocket.getInputStream())
        val clientOutput = BufferedOutputStream(clientSocket.getOutputStream())
        val serverInput = BufferedInputStream(serverSocket.getInputStream())
        val serverOutput = BufferedOutputStream(serverSocket.getOutputStream())
        
        try {
            // Keep processing requests until connection closes
            while (!clientSocket.isClosed && !serverSocket.isClosed) {
                // Read request from client
                val requestLine = readLine(clientInput) ?: break
                if (requestLine.isEmpty()) continue
                
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                
                // Read request headers
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
                
                // Forward request to server
                withContext(Dispatchers.IO) {
                    serverOutput.write(requestBuilder.toString().toByteArray())
                    
                    // Forward request body if present
                    if (contentLength > 0) {
                        val body = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val r = clientInput.read(body, read, contentLength - read)
                            if (r == -1) break
                            read += r
                        }
                        serverOutput.write(body, 0, read)
                    }
                    serverOutput.flush()
                }
                
                // Read response from server
                val responseLine = readLine(serverInput) ?: break
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
                
                // Read response body
                val responseBodyBytes = ByteArrayOutputStream()
                
                if (isChunked) {
                    readChunkedBody(serverInput, responseBodyBytes)
                } else if (responseContentLength > 0) {
                    val buffer = ByteArray(minOf(responseContentLength, BUFFER_SIZE))
                    var totalRead = 0
                    while (totalRead < responseContentLength) {
                        val toRead = minOf(buffer.size, responseContentLength - totalRead)
                        val read = withContext(Dispatchers.IO) {
                            serverInput.read(buffer, 0, toRead)
                        }
                        if (read == -1) break
                        responseBodyBytes.write(buffer, 0, read)
                        totalRead += read
                    }
                }
                
                // Forward response to client
                withContext(Dispatchers.IO) {
                    clientOutput.write(responseBuilder.toString().toByteArray())
                    clientOutput.write(responseBodyBytes.toByteArray())
                    clientOutput.flush()
                }
                
                // Intercept JSON responses for analysis
                val responseBody = responseBodyBytes.toString(StandardCharsets.UTF_8.name())
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
                
                // Check for Connection: close header
                val connectionHeader = responseHeaders["Connection"] ?: requestHeaders["Connection"]
                if (connectionHeader?.equals("close", ignoreCase = true) == true) {
                    break
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "Connection timeout for $hostname")
        } catch (e: Exception) {
            Log.e(TAG, "Error in intercepted traffic for $hostname", e)
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
                readLine(input) // Trailing CRLF
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
            
            readLine(input) // Chunk trailing CRLF
        }
    }
    
    private suspend fun handleHttpRequest(
        clientSocket: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        method: String,
        url: String,
        requestLine: String
    ) {
        try {
            val uri = URI(url)
            val hostname = uri.host
            val port = if (uri.port > 0) uri.port else 80
            val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + 
                       (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
            
            val serverSocket = Socket(hostname, port)
            serverSocket.soTimeout = SOCKET_TIMEOUT
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
        var serverSocket: Socket? = null
        try {
            serverSocket = Socket(hostname, port)
            serverSocket.soTimeout = SOCKET_TIMEOUT
            
            val job1 = scope.launch {
                tunnel(clientSocket.getInputStream(), serverSocket.getOutputStream())
            }
            val job2 = scope.launch {
                tunnel(serverSocket.getInputStream(), clientSocket.getOutputStream())
            }
            
            job1.join()
            job2.join()
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error to $hostname:$port", e)
        } finally {
            serverSocket?.closeQuietly()
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
        } catch (e: SocketTimeoutException) {
            // Timeout is expected
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
                    if (prevByte == '\r'.code && line.isNotEmpty()) {
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
    
    private fun Socket.closeQuietly() {
        try {
            this.close()
        } catch (e: Exception) { }
    }
}
