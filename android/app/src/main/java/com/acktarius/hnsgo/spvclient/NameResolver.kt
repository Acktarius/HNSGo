package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.CacheManager
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.ResourceRecord
import com.acktarius.hnsgo.SpvP2P
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record as DNSRecord
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TLSARecord
import org.xbill.DNS.Type
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Handles name resolution and proof verification
 * Manages domain name queries, Merkle proof verification, and DNS record parsing
 */
object NameResolver {
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private val sha3_256 = org.bouncycastle.crypto.digests.SHA3Digest(256)
    
    /**
     * Resolve Handshake domain to DNS records
     */
    suspend fun resolve(
        name: String,
        headerChain: List<Header>,
        chainHeight: Int,
        resolverHost: String,
        resolverPort: Int
    ): List<com.acktarius.hnsgo.Record>? = withContext(Dispatchers.IO) {
        android.util.Log.d("HNSGo", "NameResolver: Resolving Handshake domain: $name")
        
        val tld = ResourceRecord.extractTLD(name)
        android.util.Log.d("HNSGo", "NameResolver: Resolving domain '$name' -> TLD: '$tld'")
        
        val minRequiredHeight = Config.CHECKPOINT_HEIGHT + 1000
        if (chainHeight < minRequiredHeight) {
            android.util.Log.w("HNSGo", "NameResolver: Not enough headers synced (height: $chainHeight, required: $minRequiredHeight)")
        }
        
        val cached = CacheManager.get(tld, 1)
        if (cached != null) {
            android.util.Log.d("HNSGo", "NameResolver: Found cached record for TLD '$tld'")
            return@withContext parseRecords(cached)
        }

        if (chainHeight >= minRequiredHeight) {
            val tldHash = computeNameHash(tld)
            android.util.Log.d("HNSGo", "NameResolver: Querying blockchain for TLD '$tld'")
            val blockchainRecords = resolveFromBlockchain(tld, tldHash, headerChain, chainHeight)
            if (blockchainRecords != null && blockchainRecords.isNotEmpty()) {
                android.util.Log.d("HNSGo", "NameResolver: Successfully resolved TLD '$tld' from blockchain: ${blockchainRecords.size} records")
                val dnsRecords = ResourceRecord.convertHandshakeRecordsToDNS(blockchainRecords)
                if (dnsRecords.isNotEmpty()) {
                    android.util.Log.d("HNSGo", "NameResolver: Converted to ${dnsRecords.size} DNS records for '$name'")
                    val proofData = convertRecordsToProof(blockchainRecords, tld)
                    CacheManager.put(tld, 1, proofData, Config.DNS_CACHE_TTL_SECONDS)
                    return@withContext dnsRecords
                }
            }
        } else {
            android.util.Log.w("HNSGo", "NameResolver: Skipping blockchain resolution - not enough headers synced yet (height: $chainHeight)")
        }
        
        // DEVELOPMENT ONLY: Fallback to external resolver
        android.util.Log.w("HNSGo", "NameResolver: [DEV] Blockchain resolution unavailable, falling back to external resolver")
        try {
            val resolver = SimpleResolver(resolverHost).apply { port = resolverPort }
            val query = Message.newQuery(DNSRecord.newRecord(Name.fromString("$name."), Type.A, DClass.IN))
            resolver.timeout = java.time.Duration.ofSeconds(5)
            
            val response = try {
                resolver.send(query)
            } catch (e: java.net.PortUnreachableException) {
                android.util.Log.w("HNSGo", "NameResolver: Port unreachable - resolver may not be running")
                return@withContext null
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.w("HNSGo", "NameResolver: DNS query timeout")
                return@withContext null
            } catch (e: java.net.ConnectException) {
                android.util.Log.w("HNSGo", "NameResolver: Connection refused")
                return@withContext null
            }
            
            if (response.rcode == Rcode.NOERROR) {
                val records = mutableListOf<com.acktarius.hnsgo.Record>()
                val answers = response.getSection(Section.ANSWER)
                
                for (rrecord in answers) {
                    val hnsRecord: com.acktarius.hnsgo.Record? = when (rrecord) {
                        is ARecord -> {
                            val ip = rrecord.address.hostAddress
                            if (ip != null) {
                                com.acktarius.hnsgo.Record(1, ip.toByteArray())
                            } else {
                                null
                            }
                        }
                        is CNAMERecord -> {
                            val target = rrecord.target.toString(true)
                            com.acktarius.hnsgo.Record(5, target.toByteArray())
                        }
                        is TLSARecord -> null
                        else -> null
                    }
                    if (hnsRecord != null) {
                        records.add(hnsRecord)
                    }
                }
                
                if (records.isNotEmpty()) {
                    val proofData = convertRecordsToProof(records, name)
                    CacheManager.put(name, 1, proofData, Config.DNS_CACHE_TTL_SECONDS)
                    return@withContext records
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "NameResolver: Error resolving $name via DNS", e)
        }
        
        null
    }
    
    private suspend fun resolveFromBlockchain(
        name: String,
        nameHash: ByteArray,
        headerChain: List<Header>,
        chainHeight: Int
    ): List<com.acktarius.hnsgo.Record>? = withContext(Dispatchers.IO) {
        android.util.Log.d("HNSGo", "NameResolver: Resolving $name from blockchain")
        
        if (headerChain.isEmpty()) {
            android.util.Log.w("HNSGo", "NameResolver: No headers synced, cannot resolve from blockchain")
            return@withContext null
        }
        
        val latestHeader = headerChain.lastOrNull()
        if (latestHeader == null) {
            android.util.Log.w("HNSGo", "NameResolver: No headers available")
            return@withContext null
        }
        
        android.util.Log.d("HNSGo", "NameResolver: Latest header height: $chainHeight")
        android.util.Log.d("HNSGo", "NameResolver: Querying P2P peers for name hash...")
        
        try {
            // CRITICAL: Use nameRoot (name_root in hnsd) for getproof queries
            // hnsd uses pool->chain.tip->name_root for getproof queries (see pool.c:573)
            val nameQueryResult = SpvP2P.queryName(nameHash, latestHeader.nameRoot, chainHeight)
            when (nameQueryResult) {
                is SpvP2P.NameQueryResult.Success -> {
                    val (resourceRecords, proof) = nameQueryResult.records to nameQueryResult.proof
                    android.util.Log.d("HNSGo", "NameResolver: P2P query success (${resourceRecords.size} records, proof: ${proof != null})")
                    
                    // Use nameRoot (name_root) for proof verification, matching hnsd
                    if (verifyDomainProof(nameHash, emptyList(), proof, latestHeader.nameRoot)) {
                        val records = parseResourceRecords(resourceRecords)
                        if (records.isNotEmpty()) {
                            android.util.Log.d("HNSGo", "NameResolver: Successfully resolved $name from blockchain: ${records.size} records")
                            return@withContext records
                        }
                    } else {
                        android.util.Log.w("HNSGo", "NameResolver: Domain proof verification failed for $name")
                    }
                }
                is SpvP2P.NameQueryResult.NotFound -> {
                    android.util.Log.d("HNSGo", "NameResolver: Domain not found in blockchain")
                    return@withContext null
                }
                is SpvP2P.NameQueryResult.Error -> {
                    android.util.Log.w("HNSGo", "NameResolver: Error querying name from P2P peers")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "NameResolver: Exception during P2P name query", e)
        }
        
        null
    }
    
    private fun computeNameHash(name: String): ByteArray {
        val normalized = name.lowercase().trimEnd('.')
        val nameBytes = normalized.toByteArray(Charsets.UTF_8)
        sha3_256.reset()
        sha3_256.update(nameBytes, 0, nameBytes.size)
        val hash = ByteArray(32)
        sha3_256.doFinal(hash, 0)
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        android.util.Log.d("HNSGo", "NameResolver: Computed name hash for '$name': $hashHex")
        return hash
    }
    
    private fun parseResourceRecords(resourceRecords: List<ByteArray>): List<com.acktarius.hnsgo.Record> {
        val records = mutableListOf<com.acktarius.hnsgo.Record>()
        
        for (recordData in resourceRecords) {
            try {
                if (recordData.isEmpty()) {
                    continue
                }
                
                try {
                    val cbor = CBORObject.DecodeFromBytes(recordData)
                    val type = cbor["type"]?.AsInt32()
                    val data = cbor["data"]?.GetByteString()
                    
                    if (type != null && data != null) {
                        records.add(com.acktarius.hnsgo.Record(type, data))
                        continue
                    }
                } catch (e: Exception) {
                    // Not CBOR, try binary format
                }
                
                val buffer = ByteBuffer.wrap(recordData).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    val type = readVarInt(buffer)
                    val length = readVarInt(buffer)
                    
                    if (length > 0 && length <= buffer.remaining()) {
                        val data = ByteArray(length)
                        buffer.get(data)
                        records.add(com.acktarius.hnsgo.Record(type, data))
                    }
                } catch (e: Exception) {
                    try {
                        val dnsRecord = DNSRecord.fromWire(recordData, Section.ANSWER)
                        val hnsRecord: com.acktarius.hnsgo.Record? = when (dnsRecord) {
                            is ARecord -> {
                                val ip = dnsRecord.address?.hostAddress
                                if (ip != null) {
                                    com.acktarius.hnsgo.Record(1, ip.toByteArray(Charsets.UTF_8))
                                } else {
                                    null
                                }
                            }
                            is CNAMERecord -> {
                                val target = dnsRecord.target.toString(true)
                                com.acktarius.hnsgo.Record(5, target.toByteArray(Charsets.UTF_8))
                            }
                            is TLSARecord -> {
                                try {
                                    val wireData = dnsRecord.toWire(Section.ANSWER)
                                    if (wireData.size > 0) {
                                        com.acktarius.hnsgo.Record(52, wireData)
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            else -> null
                        }
                        if (hnsRecord != null) {
                            records.add(hnsRecord)
                        }
                    } catch (e2: Exception) {
                        android.util.Log.e("HNSGo", "NameResolver: Failed to parse resource record", e2)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HNSGo", "NameResolver: Error parsing resource record", e)
            }
        }
        
        return records
    }
    
    private fun readVarInt(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt() and 0xFF
        return when (first) {
            in 0..0xFC -> first
            0xFD -> {
                val value = buffer.short.toInt() and 0xFFFF
                require(value >= 0xFD) { "Non-canonical varint" }
                value
            }
            0xFE -> {
                val value = buffer.int
                require(value >= 0x10000) { "Non-canonical varint" }
                value
            }
            0xFF -> throw IllegalArgumentException("Varint too large (64-bit not supported)")
            else -> throw IllegalArgumentException("Invalid varint prefix: $first")
        }
    }
    
    private fun verifyDomainProof(
        nameHash: ByteArray,
        records: List<com.acktarius.hnsgo.Record>,
        proof: ByteArray?,
        nameRoot: ByteArray
    ): Boolean {
        android.util.Log.d("HNSGo", "NameResolver: Verifying domain proof")
        
        if (proof == null) {
            android.util.Log.w("HNSGo", "NameResolver: No proof provided for verification")
            return false
        }
        
        if (nameHash.size != 32 || nameRoot.size != 32) {
            android.util.Log.w("HNSGo", "NameResolver: Invalid hash size")
            return false
        }
        
        try {
            val proofNodes = parseMerkleProof(proof)
            if (proofNodes.isEmpty()) {
                android.util.Log.w("HNSGo", "NameResolver: Failed to parse Merkle proof")
                return false
            }
            
            android.util.Log.d("HNSGo", "NameResolver: Parsed ${proofNodes.size} Merkle proof nodes")
            
            val calculatedRoot = reconstructMerkleRoot(nameHash, records, proofNodes)
            
            if (calculatedRoot == null) {
                android.util.Log.w("HNSGo", "NameResolver: Failed to reconstruct Merkle root")
                return false
            }
            
            val isValid = calculatedRoot.contentEquals(nameRoot)
            
            if (isValid) {
                android.util.Log.d("HNSGo", "NameResolver: Domain proof verified successfully")
            } else {
                android.util.Log.w("HNSGo", "NameResolver: Domain proof verification failed - root mismatch")
            }
            
            return isValid
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "NameResolver: Error verifying domain proof", e)
            return false
        }
    }
    
    private fun parseMerkleProof(proof: ByteArray): List<ByteArray> {
        val nodes = mutableListOf<ByteArray>()
        
        try {
            val cbor = CBORObject.DecodeFromBytes(proof)
            val nodesArray = cbor["nodes"] ?: cbor["proof"] ?: cbor
            
            try {
                val size = nodesArray.size()
                for (i in 0 until size) {
                    val node = nodesArray[i].GetByteString()
                    if (node != null && node.size == 32) {
                        nodes.add(node)
                    }
                }
                if (nodes.isNotEmpty()) {
                    android.util.Log.d("HNSGo", "NameResolver: Parsed ${nodes.size} proof nodes from CBOR")
                    return nodes
                }
            } catch (e: Exception) {
                // Not an array, continue to binary format
            }
        } catch (e: Exception) {
            // Not CBOR, try binary format
        }
        
        try {
            val buffer = ByteBuffer.wrap(proof).order(ByteOrder.LITTLE_ENDIAN)
            val count = readVarInt(buffer)
            
            if (count > 0 && count <= 256) {
                for (i in 0 until count) {
                    if (buffer.remaining() >= 32) {
                        val node = ByteArray(32)
                        buffer.get(node)
                        nodes.add(node)
                    } else {
                        break
                    }
                }
                if (nodes.isNotEmpty()) {
                    android.util.Log.d("HNSGo", "NameResolver: Parsed ${nodes.size} proof nodes from binary format")
                    return nodes
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("HNSGo", "NameResolver: Error parsing binary proof format", e)
        }
        
        if (proof.size == 32) {
            nodes.add(proof)
            return nodes
        } else if (proof.size % 32 == 0) {
            for (i in 0 until proof.size step 32) {
                val node = ByteArray(32)
                System.arraycopy(proof, i, node, 0, 32)
                nodes.add(node)
            }
            return nodes
        }
        
        android.util.Log.w("HNSGo", "NameResolver: Could not parse proof in any format (size: ${proof.size})")
        return emptyList()
    }
    
    private fun reconstructMerkleRoot(
        nameHash: ByteArray,
        records: List<com.acktarius.hnsgo.Record>,
        proofNodes: List<ByteArray>
    ): ByteArray? {
        try {
            val recordsData = serializeRecords(records)
            
            val leafData = ByteArray(nameHash.size + recordsData.size).apply {
                System.arraycopy(nameHash, 0, this, 0, nameHash.size)
                System.arraycopy(recordsData, 0, this, nameHash.size, recordsData.size)
            }
            
            var currentHash = doubleSha256(leafData)
            
            for ((index, proofNode) in proofNodes.withIndex()) {
                if (proofNode.size != 32) {
                    android.util.Log.w("HNSGo", "NameResolver: Invalid proof node size at index $index: ${proofNode.size}")
                    return null
                }
                
                val comparison = compareHashes(currentHash, proofNode)
                val combined = if (comparison < 0) {
                    ByteArray(64).apply {
                        System.arraycopy(currentHash, 0, this, 0, 32)
                        System.arraycopy(proofNode, 0, this, 32, 32)
                    }
                } else {
                    ByteArray(64).apply {
                        System.arraycopy(proofNode, 0, this, 0, 32)
                        System.arraycopy(currentHash, 0, this, 32, 32)
                    }
                }
                
                currentHash = doubleSha256(combined)
            }
            
            return currentHash
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "NameResolver: Error reconstructing Merkle root", e)
            return null
        }
    }
    
    private fun serializeRecords(records: List<com.acktarius.hnsgo.Record>): ByteArray {
        val buffer = ByteArrayOutputStream()
        
        writeVarInt(buffer, records.size)
        
        for (record in records) {
            writeVarInt(buffer, record.type)
            writeVarInt(buffer, record.data.size)
            buffer.write(record.data)
        }
        
        return buffer.toByteArray()
    }
    
    private fun compareHashes(hash1: ByteArray, hash2: ByteArray): Int {
        require(hash1.size == 32 && hash2.size == 32) { "Hashes must be 32 bytes" }
        
        for (i in 0 until 32) {
            val cmp = (hash1[i].toInt() and 0xFF).compareTo(hash2[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return 0
    }
    
    private fun doubleSha256(data: ByteArray): ByteArray {
        sha256.reset()
        val first = sha256.digest(data)
        sha256.reset()
        return sha256.digest(first)
    }
    
    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        when {
            value < 0xFD -> {
                output.write(value)
            }
            value <= 0xFFFF -> {
                output.write(0xFD)
                val bytes = ByteArray(2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
                output.write(bytes)
            }
            else -> {
                output.write(0xFE)
                val bytes = ByteArray(4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
                output.write(bytes)
            }
        }
    }
    
    private fun convertRecordsToProof(records: List<com.acktarius.hnsgo.Record>, name: String): ByteArray {
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
    
    private fun parseRecords(proof: ByteArray): List<com.acktarius.hnsgo.Record> {
        val cbor = CBORObject.DecodeFromBytes(proof)
        val records = mutableListOf<com.acktarius.hnsgo.Record>()
        val res = cbor["resource"]
        val dataArray = res["data"]
        if (dataArray != null) {
            try {
                val arraySize = dataArray.size()
                for (i in 0 until arraySize) {
                    val r = dataArray[i]
                    val type = r["type"]?.AsInt32() ?: continue
                    val dataBytes = r["data"]?.GetByteString() ?: continue
                    when (type) {
                        1 -> records.add(com.acktarius.hnsgo.Record(1, dataBytes))
                        5 -> records.add(com.acktarius.hnsgo.Record(5, dataBytes))
                        52 -> records.add(com.acktarius.hnsgo.Record(52, dataBytes))
                    }
                }
            } catch (e: Exception) {
                // Not an array, skip
            }
        }
        return records
    }
}

