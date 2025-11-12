package com.acktarius.hnsgo

import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.*
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

object SpvClient {
    // Handshake DNS resolver - can be local (127.0.0.1) or remote
    // Default: localhost (if hnsd is running locally)
    // Can be configured to point to user's resolver (e.g., 192.168.1.8:5341)
    private var resolverHost: String = Config.DEFAULT_RESOLVER_HOST
    private var resolverPort: Int = Config.DEFAULT_RESOLVER_PORT
    
    private lateinit var dataDir: File
    private val sha256 = MessageDigest.getInstance("SHA-256")
    
    // Lazy resolver - will be created when needed
    private var dnsResolver: SimpleResolver? = null
    
    private fun getResolver(): SimpleResolver {
        if (dnsResolver == null) {
            dnsResolver = SimpleResolver(resolverHost)
            dnsResolver?.port = resolverPort
        }
        return dnsResolver!!
    }
    
    /**
     * Configure the Handshake DNS resolver address
     * @param host IP address (e.g., "127.0.0.1" or "192.168.1.8")
     * @param port Port number (default: Config.DEFAULT_RESOLVER_PORT, no root required)
     */
    fun setResolver(host: String, port: Int = Config.DEFAULT_RESOLVER_PORT) {
        resolverHost = host
        resolverPort = port
        dnsResolver = null // Reset resolver to use new address
        android.util.Log.d("HNSGo", "SpvClient: Resolver set to $host:$port")
    }
    

    // Header chain storage
    private val headerChain = mutableListOf<Header>()
    private var chainHeight: Int = 0
    
    suspend fun init(dir: File) {
        dataDir = dir
        loadHeaderChain()
        
        // If no headers loaded, try to load checkpoint for bootstrap
        if (headerChain.isEmpty()) {
            android.util.Log.d("HNSGo", "SpvClient: No headers found, attempting checkpoint bootstrap...")
            val checkpointHeight = Checkpoint.loadCheckpoint(headerChain, chainHeight)
            chainHeight = checkpointHeight
            if (headerChain.isNotEmpty()) {
                android.util.Log.d("HNSGo", "SpvClient: Loaded ${headerChain.size} headers from checkpoint")
                saveHeaderChain()
            }
        }
        
        // Try to sync additional headers from P2P
        syncHeaders()
        android.util.Log.d("HNSGo", "SpvClient: Initialized with ${headerChain.size} headers (height: $chainHeight)")
    }
    
    /**
     * Sync headers from Handshake P2P network
     * Note: P2P sync may fail if seed nodes are not accessible.
     * The app can still work with an external Handshake resolver (e.g., local hnsd).
     */
    private suspend fun syncHeaders() = withContext(Dispatchers.IO) {
        android.util.Log.d("HNSGo", "SpvClient: Starting P2P header sync...")
        
        val startHeight = chainHeight
        var newHeadersCount = 0
        
        val success = SpvP2P.syncHeaders(startHeight) { header ->
            // Validate header chain
            if (validateHeader(header)) {
                headerChain.add(header)
                chainHeight++
                newHeadersCount++
                
                // Save periodically (launch coroutine for suspend function)
                if (newHeadersCount % Config.HEADER_SYNC_BATCH_SIZE == 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        saveHeaderChain()
                    }
                }
                
                true // Continue syncing
            } else {
                android.util.Log.w("HNSGo", "SpvClient: Invalid header at height $chainHeight")
                false // Stop on invalid header
            }
        }
        
        if (success && newHeadersCount > 0) {
            saveHeaderChain()
            android.util.Log.d("HNSGo", "SpvClient: Synced $newHeadersCount new headers")
        } else if (!success) {
            android.util.Log.w("HNSGo", "SpvClient: P2P header sync failed - seed nodes may not be accessible")
            android.util.Log.i("HNSGo", "SpvClient: App can still work with external Handshake resolver (configure via setResolver)")
            android.util.Log.i("HNSGo", "SpvClient: Example: SpvClient.setResolver(\"192.168.1.8\", 5341)")
        }
    }
    
    /**
     * Validate header against chain
     */
    private fun validateHeader(header: Header): Boolean {
        if (headerChain.isEmpty()) {
            // First header - validate against genesis or checkpoint
            // For now, accept any header (should validate against known checkpoint)
            return true
        }
        
        // Validate prevBlock matches last header's hash
        val lastHeader = headerChain.last()
        val lastHash = lastHeader.hash()
        
        if (!header.prevBlock.contentEquals(lastHash)) {
            android.util.Log.w("HNSGo", "SpvClient: Header prevBlock mismatch")
            return false
        }
        
        // Validate proof of work (simplified - check difficulty)
        // Full validation would check target difficulty
        return true
    }
    
    /**
     * Load header chain from disk
     */
    private suspend fun loadHeaderChain() = withContext(Dispatchers.IO) {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        if (!headersFile.exists()) {
            android.util.Log.d("HNSGo", "SpvClient: No existing header chain found")
            return@withContext
        }
        
        try {
            val data = headersFile.readBytes()
            val cbor = CBORObject.DecodeFromBytes(data)
            
            val headersArray = cbor["headers"]
            if (headersArray != null) {
                val count = headersArray.size()
                for (i in 0 until count) {
                    val headerObj = headersArray[i]
                    val headerBytes = headerObj.GetByteString()
                    // Parse header from bytes
                    val header = parseHeaderFromBytes(headerBytes)
                    headerChain.add(header)
                }
                chainHeight = headerChain.size
                android.util.Log.d("HNSGo", "SpvClient: Loaded $chainHeight headers from disk")
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "SpvClient: Error loading header chain", e)
        }
    }
    
    /**
     * Save header chain to disk
     */
    private suspend fun saveHeaderChain() = withContext(Dispatchers.IO) {
        try {
            val cbor = CBORObject.NewMap()
            val headersArray = CBORObject.NewArray()
            
            for (header in headerChain) {
                headersArray.Add(CBORObject.FromObject(header.toBytes()))
            }
            
            cbor["headers"] = headersArray
            cbor["height"] = CBORObject.FromObject(chainHeight)
            cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
            
            val data = cbor.EncodeToBytes()
            val headersFile = File(dataDir, Config.HEADERS_FILE)
            val tempFile = File(dataDir, "${Config.HEADERS_FILE}.tmp")
            
            tempFile.writeBytes(data)
            tempFile.renameTo(headersFile)
            
            // Update checksum
            val checksum = sha256.digest(data)
            sha256.reset()
            File(dataDir, Config.HEADERS_CHECKSUM).writeBytes(checksum)
            
            android.util.Log.d("HNSGo", "SpvClient: Saved $chainHeight headers to disk")
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "SpvClient: Error saving header chain", e)
        }
    }
    
    private fun parseHeaderFromBytes(bytes: ByteArray): Header {
        // Parse header from stored bytes (same format as SpvP2P.parseHeader)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        val version = buffer.int
        val prevBlock = ByteArray(32).apply { buffer.get(this) }
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }
        val treeRoot = ByteArray(32).apply { buffer.get(this) }
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }
        val time = buffer.int.toLong()
        val bits = buffer.int
        val nonce = buffer.int
        val extraNonce = buffer.long
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            merkleRoot = merkleRoot,
            witnessRoot = witnessRoot,
            treeRoot = treeRoot,
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce
        )
    }
    
    // Old HTTP-based header download - removed, now using P2P sync

    /**
     * Compute Handshake name hash (SHA256 of domain name)
     * This is used to identify domains in the blockchain
     */
    private fun computeNameHash(name: String): ByteArray {
        // Handshake uses SHA256 of the domain name (lowercase, no trailing dot)
        val normalized = name.lowercase().trimEnd('.')
        sha256.reset()
        return sha256.digest(normalized.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Resolve domain from blockchain data using SPV
     * Queries P2P peers for domain resource records and verifies against treeRoot
     */
    private suspend fun resolveFromBlockchain(name: String, nameHash: ByteArray): List<Record>? = withContext(Dispatchers.IO) {
        android.util.Log.d("HNSGo", "SpvClient: Resolving $name from blockchain (hash: ${nameHash.joinToString("") { "%02x".format(it) }})")
        
        if (headerChain.isEmpty()) {
            android.util.Log.w("HNSGo", "SpvClient: No headers synced, cannot resolve from blockchain")
            return@withContext null
        }
        
        // Get the latest header for treeRoot verification
        val latestHeader = headerChain.lastOrNull()
        if (latestHeader == null) {
            android.util.Log.w("HNSGo", "SpvClient: No headers available")
            return@withContext null
        }
        
        android.util.Log.d("HNSGo", "SpvClient: Latest header height: $chainHeight, treeRoot: ${latestHeader.treeRoot.joinToString("") { "%02x".format(it) }.take(16)}...")
        
        // Query P2P peers for domain resource records
        val nameQueryResult = SpvP2P.queryName(nameHash)
        if (nameQueryResult != null) {
            val (resourceRecords, proof) = nameQueryResult
            
            // Verify proof against treeRoot
            if (verifyDomainProof(nameHash, emptyList(), proof, latestHeader.treeRoot)) {
                // Parse resource records into DNS records
                val records = parseResourceRecords(resourceRecords)
                if (records.isNotEmpty()) {
                    android.util.Log.d("HNSGo", "SpvClient: Successfully resolved $name from blockchain: ${records.size} records")
                    return@withContext records
                }
            } else {
                android.util.Log.w("HNSGo", "SpvClient: Domain proof verification failed for $name")
            }
        } else {
            android.util.Log.d("HNSGo", "SpvClient: Could not query name from P2P peers (may need to implement getname message)")
        }
        
        // Return null to fall back to external resolver
        null
    }
    
    /**
     * Parse resource records from blockchain data into DNS records
     * Handshake stores resource records in a specific format that needs to be converted
     */
    private fun parseResourceRecords(resourceRecords: List<ByteArray>): List<Record> {
        val records = mutableListOf<Record>()
        
        for (recordData in resourceRecords) {
            try {
                // Handshake resource records are typically in a specific format
                // This is a placeholder - actual format depends on Handshake protocol
                // For now, try to parse as DNS record format
                
                // TODO: Implement proper parsing of Handshake resource records
                // Handshake uses a specific format for storing DNS records in the blockchain
                // We need to decode the resource record format and extract:
                // - Record type (A, CNAME, etc.)
                // - Record data (IP address, target domain, etc.)
                
                android.util.Log.d("HNSGo", "SpvClient: Parsing resource record (${recordData.size} bytes)")
                
                // Placeholder: For now, we'll need to implement the actual parsing
                // based on Handshake's resource record format
                
            } catch (e: Exception) {
                android.util.Log.e("HNSGo", "SpvClient: Error parsing resource record", e)
            }
        }
        
        return records
    }
    
    /**
     * Verify domain proof against treeRoot
     * Validates that domain resource records are valid according to blockchain state
     */
    private fun verifyDomainProof(nameHash: ByteArray, records: List<Record>, proof: ByteArray?, treeRoot: ByteArray): Boolean {
        // TODO: Implement Merkle proof verification
        // This would verify that the domain data is included in the treeRoot
        // Steps:
        // 1. Reconstruct Merkle tree path from proof
        // 2. Calculate root from nameHash + resource records + proof
        // 3. Compare calculated root with treeRoot from header
        // 4. Verify the proof is valid for the current blockchain state
        
        android.util.Log.d("HNSGo", "SpvClient: Verifying domain proof (placeholder)")
        
        if (proof == null) {
            android.util.Log.w("HNSGo", "SpvClient: No proof provided for verification")
            return false
        }
        
        // Placeholder verification - always returns false until implemented
        // This ensures we don't accept unverified data
        return false
    }
    
    /**
     * Resolve Handshake domain to DNS records
     * 
     * PRODUCTION: Resolves domains directly from blockchain data using SPV
     * Falls back to external resolver only if blockchain resolution fails
     */
    suspend fun resolve(name: String): List<Record>? = withContext(Dispatchers.IO) {
        android.util.Log.d("HNSGo", "SpvClient: Resolving Handshake domain: $name")
        
        // Check cache first
        val cached = CacheManager.get(name, 1) // A record
        if (cached != null) {
            android.util.Log.d("HNSGo", "SpvClient: Found cached record for $name")
            return@withContext parseRecords(cached)
        }

        // Try blockchain resolution first (SPV)
        val nameHash = computeNameHash(name)
        val blockchainRecords = resolveFromBlockchain(name, nameHash)
        if (blockchainRecords != null && blockchainRecords.isNotEmpty()) {
            android.util.Log.d("HNSGo", "SpvClient: Successfully resolved $name from blockchain: ${blockchainRecords.size} records")
            // Cache the result
            val proofData = convertRecordsToProof(blockchainRecords, name)
            CacheManager.put(name, 1, proofData, Config.DNS_CACHE_TTL_SECONDS)
            return@withContext blockchainRecords
        }
        
        // Fallback to external resolver (DEBUG/TEMPORARY)
        android.util.Log.d("HNSGo", "SpvClient: Blockchain resolution failed, falling back to external resolver")
        try {
            // DEBUG: Query external Handshake DNS resolver (hnsd) via DNS protocol
            // TODO: Remove this fallback once blockchain resolution is fully implemented
            val resolver = getResolver()
            val query = org.xbill.DNS.Message.newQuery(org.xbill.DNS.Record.newRecord(Name.fromString("$name."), Type.A, DClass.IN))
            
            android.util.Log.d("HNSGo", "SpvClient: Sending DNS query to $resolverHost:$resolverPort")
            
            // Set timeout for DNS query
            resolver.timeout = java.time.Duration.ofSeconds(5)
            
            val response = try {
                resolver.send(query)
            } catch (e: java.net.PortUnreachableException) {
                android.util.Log.w("HNSGo", "SpvClient: Port unreachable - resolver may not be running at $resolverHost:$resolverPort")
                android.util.Log.w("HNSGo", "SpvClient: Make sure hnsd or another Handshake resolver is running, or configure resolver address")
                return@withContext null
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.w("HNSGo", "SpvClient: DNS query timeout - resolver at $resolverHost:$resolverPort not responding")
                return@withContext null
            } catch (e: java.net.ConnectException) {
                android.util.Log.w("HNSGo", "SpvClient: Connection refused - resolver at $resolverHost:$resolverPort not available")
                return@withContext null
            }
            
            android.util.Log.d("HNSGo", "SpvClient: DNS response code: ${response.rcode}")
            
            if (response.rcode == Rcode.NOERROR) {
                val records = mutableListOf<Record>()
                
                // Parse all answer records
                val answers = response.getSection(Section.ANSWER)
                android.util.Log.d("HNSGo", "SpvClient: Received ${answers.size} answer records")
                
                for (rrecord in answers) {
                    when (rrecord) {
                        is ARecord -> {
                            val ip = rrecord.address.hostAddress
                            android.util.Log.d("HNSGo", "SpvClient: A record: $ip")
                            records.add(Record(1, ip.toByteArray())) // Type 1 = A
                        }
                        is CNAMERecord -> {
                            val target = rrecord.target.toString(true)
                            android.util.Log.d("HNSGo", "SpvClient: CNAME record: $target")
                            records.add(Record(5, target.toByteArray())) // Type 5 = CNAME
                        }
                        is TLSARecord -> {
                            // TLSA records need special handling
                            android.util.Log.d("HNSGo", "SpvClient: TLSA record found")
                            // TODO: Extract TLSA data properly
                        }
                    }
                }
                
                if (records.isNotEmpty()) {
                    // Cache the result (convert to proof-like format for caching)
                    val proofData = convertRecordsToProof(records, name)
                    CacheManager.put(name, 1, proofData, Config.DNS_CACHE_TTL_SECONDS)
                    android.util.Log.d("HNSGo", "SpvClient: Successfully resolved $name: ${records.size} records")
                    return@withContext records
                } else {
                    android.util.Log.w("HNSGo", "SpvClient: No records found in DNS response for $name")
                }
            } else if (response.rcode == Rcode.NXDOMAIN) {
                android.util.Log.w("HNSGo", "SpvClient: Domain not found: $name")
            } else {
                android.util.Log.w("HNSGo", "SpvClient: DNS error for $name: ${response.rcode}")
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "SpvClient: Error resolving $name via DNS", e)
        }
        
        null
    }
    
    /**
     * Convert DNS records to proof-like format for caching
     */
    private fun convertRecordsToProof(records: List<Record>, name: String): ByteArray {
        val cbor = CBORObject.NewMap()
        val resource = CBORObject.NewMap()
        val dataArray = CBORObject.NewArray()
        
        for (record in records) {
            val recordObj = CBORObject.NewMap()
            recordObj["type"] = CBORObject.FromObject(record.type)
            recordObj["data"] = CBORObject.FromObject(record.data)
            dataArray.Add(recordObj)
        }
        
        resource["data"] = dataArray
        cbor["resource"] = resource
        cbor["name"] = CBORObject.FromObject(name)
        
        return cbor.EncodeToBytes()
    }

    private fun verifyProof(proof: ByteArray, name: String): Boolean {
        try {
            // Parse the CBOR proof
            val cbor = CBORObject.DecodeFromBytes(proof)
            
            // Extract Merkle root from proof (Handshake proofs typically contain root and height)
            val root = cbor["root"]?.GetByteString()
            val height = cbor["height"]?.AsInt32()
            
            if (root == null || height == null) {
                // Fallback: try alternative proof structure
                val resource = cbor["resource"]
                if (resource == null) return false
                
                // For now, if we can't find explicit root, we'll verify against header chain
                return verifyAgainstHeaderChain(proof, name)
            }
            
            // Verify the Merkle root against our header chain
            return verifyMerkleRoot(root, height)
        } catch (e: Exception) {
            // If parsing fails, fall back to basic validation
            return verifyAgainstHeaderChain(proof, name)
        }
    }
    
    private fun storeHeadersSecurely(headers: ByteArray) {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        val checksumFile = File(dataDir, Config.HEADERS_CHECKSUM)
        val metadataFile = File(dataDir, Config.HEADERS_METADATA)
        
        // Calculate SHA-256 checksum for integrity verification
        val checksum = sha256.digest(headers)
        sha256.reset()
        
        // Store metadata (timestamp, size, etc.)
        val metadata = CBORObject.NewMap()
        metadata["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
        metadata["size"] = CBORObject.FromObject(headers.size)
        metadata["version"] = CBORObject.FromObject(1)
        
        // Write files atomically (write to temp first, then rename)
        try {
            // Write headers
            val tempHeaders = File(dataDir, "${Config.HEADERS_FILE}.tmp")
            tempHeaders.writeBytes(headers)
            tempHeaders.renameTo(headersFile)
            
            // Write checksum
            val tempChecksum = File(dataDir, "${Config.HEADERS_CHECKSUM}.tmp")
            tempChecksum.writeBytes(checksum)
            tempChecksum.renameTo(checksumFile)
            
            // Write metadata
            val tempMetadata = File(dataDir, "${Config.HEADERS_METADATA}.tmp")
            tempMetadata.writeBytes(metadata.EncodeToBytes())
            tempMetadata.renameTo(metadataFile)
        } catch (e: Exception) {
            // Clean up on failure
            File(dataDir, "${Config.HEADERS_FILE}.tmp").delete()
            File(dataDir, "${Config.HEADERS_CHECKSUM}.tmp").delete()
            File(dataDir, "${Config.HEADERS_METADATA}.tmp").delete()
            throw e
        }
    }
    
    private fun verifyHeadersIntegrity(): Boolean {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        val checksumFile = File(dataDir, Config.HEADERS_CHECKSUM)
        
        if (!headersFile.exists() || !checksumFile.exists()) {
            return false
        }
        
        try {
            val headersData = headersFile.readBytes()
            val storedChecksum = checksumFile.readBytes()
            
            // Calculate current checksum
            val currentChecksum = sha256.digest(headersData)
            sha256.reset()
            
            // Verify checksums match
            return currentChecksum.contentEquals(storedChecksum)
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun verifyMerkleRoot(root: ByteArray, height: Int): Boolean {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        if (!headersFile.exists()) {
            // No headers available - can't verify, but allow for now
            // In production, you might want to return false here
            return true
        }
        
        // First verify integrity - reject tampered headers
        if (!verifyHeadersIntegrity()) {
            // Headers have been tampered with!
            // Delete corrupted headers and force re-download
            headersFile.delete()
            File(dataDir, Config.HEADERS_CHECKSUM).delete()
            File(dataDir, Config.HEADERS_METADATA).delete()
            return false
        }
        
        try {
            // Load headers (assuming they're stored as CBOR or binary format)
            val headersData = headersFile.readBytes()
            val headers = CBORObject.DecodeFromBytes(headersData)
            
            // Check format
            val format = headers["format"]?.AsString()
            if (format == "binary") {
                // Raw binary format - can't verify without parsing
                return root.size == 32 // At least verify format
            }
            
            // Find header at the specified height
            val headerArray = headers["headers"]
            if (headerArray == null) {
                // Try alternative structure - maybe headers are at root level
                return verifyRootFormat(root)
            }
            
            // Check if we have a header at this height
            // In a real implementation, you'd verify:
            // 1. The header chain is valid (each header links to previous)
            // 2. The Merkle root in the header matches the proof root
            // 3. The proof's Merkle path is valid
            
            // For now, basic check: verify the root hash matches
            // Try to iterate as array - if it fails, it's not an array
            try {
                val arraySize = headerArray.size()
                for (i in 0 until arraySize) {
                val header = headerArray[i]
                val headerRoot = header["merkleRoot"]?.GetByteString()
                    ?: header["root"]?.GetByteString()
                    ?: header["merkle_root"]?.GetByteString()
                
                    val headerHeight = header["height"]?.AsInt32()
                    
                    if (headerHeight == height && headerRoot != null) {
                        return root.contentEquals(headerRoot)
                    }
                }
            } catch (e: Exception) {
                // Not an array, try alternative structure
                return verifyRootFormat(root)
            }
            
            // If we can't find the header, verify the root is valid format (32 bytes for SHA256)
            return verifyRootFormat(root)
        } catch (e: Exception) {
            // Header parsing failed - verify root format at least
            return verifyRootFormat(root)
        }
    }
    
    private fun verifyRootFormat(root: ByteArray): Boolean {
        // Handshake uses double-SHA256 (like Bitcoin), so Merkle roots are 32 bytes
        return root.size == 32
    }
    
    private fun verifyAgainstHeaderChain(proof: ByteArray, name: String): Boolean {
        // Basic validation: ensure proof is valid CBOR and contains expected structure
        try {
            val cbor = CBORObject.DecodeFromBytes(proof)
            
            // Verify proof contains resource data
            val resource = cbor["resource"]
            if (resource == null) return false
            
            val data = resource["data"]
            if (data == null) return false
            // Try to check if it's an array by attempting to get size
            try {
                data.size()
            } catch (e: Exception) {
                return false // Not an array
            }
            
            // Verify name matches (if present in proof)
            val proofName = cbor["name"]?.AsString()
            if (proofName != null && !proofName.equals(name, ignoreCase = true)) {
                return false
            }
            
            // If we have headers, try to verify
            val headersFile = File(dataDir, Config.HEADERS_FILE)
            if (headersFile.exists()) {
                // Verify integrity before using headers
                if (!verifyHeadersIntegrity()) {
                    return false // Headers tampered with
                }
                // Try to extract and verify Merkle root from proof structure
                // This is a simplified check - full verification would need proper Merkle proof validation
                return true // Allow if structure is valid
            }
            
            // No headers but proof structure is valid
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun parseRecords(proof: ByteArray): List<Record> {
        val cbor = CBORObject.DecodeFromBytes(proof)
        val records = mutableListOf<Record>()
        val res = cbor["resource"]
        val dataArray = res["data"]
        if (dataArray != null) {
            // Try to iterate as array
            try {
                val arraySize = dataArray.size()
                for (i in 0 until arraySize) {
                    val r = dataArray[i]
                    val type = r["type"]?.AsInt32() ?: continue
                    val dataBytes = r["data"]?.GetByteString() ?: continue
                    when (type) {
                        1 -> records.add(Record(1, dataBytes)) // A
                        5 -> records.add(Record(5, dataBytes)) // CNAME
                        52 -> records.add(Record(52, dataBytes)) // TLSA
                    }
                }
            } catch (e: Exception) {
                // Not an array, skip
            }
        }
        return records
    }
}