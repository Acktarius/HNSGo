package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.CacheManager
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.ResourceRecord
import com.acktarius.hnsgo.SpvP2P
import com.acktarius.hnsgo.spvclient.Proof
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.Record as DNSRecord
import org.xbill.DNS.Section
import org.xbill.DNS.TLSARecord
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
        chainHeight: Int
    ): List<com.acktarius.hnsgo.Record>? = withContext(Dispatchers.IO) {
        
        val tld = ResourceRecord.extractTLD(name)
        
        // Check blacklist (matching hnsd ns.c:475-503)
        // Blacklisted TLDs are reserved for other naming systems and should return NXDOMAIN
        if (Config.BLACKLISTED_TLDS.contains(tld.lowercase())) {
            return@withContext null
        }
        
        val minRequiredHeight = Config.CHECKPOINT_HEIGHT + 1000
        if (chainHeight < minRequiredHeight) {
        }
        
        // Use (qname, qtype, qclass) as cache key - for TLD proof cache, use DClass.IN
        val cached = CacheManager.get(tld, 1, org.xbill.DNS.DClass.IN, chainHeight)
        if (cached != null) {
            // Parse cached Handshake records and convert to DNS format (matching fresh query behavior)
            val cachedHandshakeRecords = parseRecords(cached)
            if (cachedHandshakeRecords.isNotEmpty()) {
                val cachedDnsRecords = ResourceRecord.convertHandshakeRecordsToDNS(cachedHandshakeRecords)
                return@withContext cachedDnsRecords
            }
        }

        if (chainHeight >= minRequiredHeight) {
            val tldHash = computeNameHash(tld)
            val tldHashHex = tldHash.joinToString("") { "%02x".format(it) }
            val blockchainRecords = resolveFromBlockchain(tld, tldHash, headerChain, chainHeight)
            if (blockchainRecords != null && blockchainRecords.isNotEmpty()) {
                val dnsRecords = ResourceRecord.convertHandshakeRecordsToDNS(blockchainRecords)
                if (dnsRecords.isNotEmpty()) {
                    val proofData = convertRecordsToProof(blockchainRecords, tld)
                    // Use (qname, qtype, qclass) as cache key - for TLD proof cache, use DClass.IN
                    CacheManager.put(tld, 1, org.xbill.DNS.DClass.IN, proofData, Config.DNS_CACHE_TTL_SECONDS, chainHeight)
                    return@withContext dnsRecords
                }
            }
        } else {
        }
        
        null
    }
    
    private suspend fun resolveFromBlockchain(
        name: String,
        nameHash: ByteArray,
        headerChain: List<Header>,
        chainHeight: Int
    ): List<com.acktarius.hnsgo.Record>? = withContext(Dispatchers.IO) {
        
        if (headerChain.isEmpty()) {
            return@withContext null
        }
        
        val latestHeader = headerChain.lastOrNull()
        if (latestHeader == null) {
            return@withContext null
        }
        
        
        // Log last 10 headers for debugging
        val firstHeaderHeight = chainHeight - headerChain.size + 1
        val lastHeaders = headerChain.takeLast(10)
        lastHeaders.forEachIndexed { index, header ->
            val headerHeight = firstHeaderHeight + (headerChain.size - lastHeaders.size + index)
            val rootHex = header.nameRoot.joinToString("") { "%02x".format(it) }
        }
        
        // EXACT MATCH to hnsd's hsk_chain_safe_root (chain.c:180-209)
        // hnsd pool.c:521: const uint8_t *root = hsk_chain_safe_root(&pool->chain);
        val interval = Config.TREE_INTERVAL
        var mod = chainHeight % interval
        
        // If there's enough proof-of-work on top of the most recent root, it should be safe to use it
        // EXACT MATCH to hnsd chain.c:197-198
        if (mod >= 12) {
            mod = 0
        }
        
        // EXACT MATCH to hnsd chain.c:200: uint32_t height = (uint32_t)chain->height - mod;
        val rootHeight = chainHeight - mod
        
        // Check if tip root is different from committed interval root
        // If root changed at tip, peers may have indexed the new root
        val tipRoot = latestHeader.nameRoot
        val committedRoot = getSafeRootAtHeight(headerChain, chainHeight, rootHeight)
        
        val tipRootHex = tipRoot.joinToString("") { "%02x".format(it) }
        val committedRootHex = committedRoot?.joinToString("") { "%02x".format(it) } ?: "null"
        
        // If tip root is different from committed root, use tip root (peers have indexed it)
        val finalRootHeight = if (committedRoot != null && !tipRoot.contentEquals(committedRoot)) {
            chainHeight
        } else {
            rootHeight
        }
        
        // Verify header chain has the header at finalRootHeight
        if (finalRootHeight < firstHeaderHeight || finalRootHeight > chainHeight) {
            return@withContext null
        }
        
        val safeRoot = getSafeRootAtHeight(headerChain, chainHeight, finalRootHeight)
        if (safeRoot == null) {
            return@withContext null
        }
        
        val rootHex = safeRoot.joinToString("") { "%02x".format(it) }
        
        try {
            // Query with safe root - ConnectionManager will handshake first, get peer height,
            // then adjust root based on peer's height before sending query
            val nameQueryResult = SpvP2P.queryName(nameHash, safeRoot, chainHeight, headerChain)
            
            val finalResult = nameQueryResult
                
            when (finalResult) {
                is SpvP2P.NameQueryResult.Success -> {
                    val proof = finalResult.proof
                    
                    if (proof == null) {
                        return@withContext null
                    }
                    
                    // Use safe root for verification
                    val verifiedRoot = safeRoot
                    
                    // Use Proof.kt to verify proof (matching hnsd's hsk_proof_verify)
                    val parsedProof = Proof.parseProof(proof)
                    if (parsedProof != null) {
                        val verifyResult = Proof.verifyProof(verifiedRoot, nameHash, parsedProof)
                        
                        if (verifyResult.success && verifyResult.exists && verifyResult.data != null) {
                            // Parse resource records from proof data (extracted from name state)
                            val records = parseResourceRecordsFromProof(verifyResult.data)
                            if (records.isNotEmpty()) {
                                return@withContext records
                            } else {
                            }
                        } else if (verifyResult.success && !verifyResult.exists) {
                            return@withContext null
                        } else {
                        }
                    } else {
                    }
                }
                is SpvP2P.NameQueryResult.NotFound -> {
                    // Bounded fallback: try previous committed interval root (max 2 attempts)
                    val treeInterval = Config.TREE_INTERVAL
                    val currentMod = chainHeight % treeInterval
                    val currentCommittedHeight = chainHeight - currentMod
                    val previousCommittedHeight = currentCommittedHeight - treeInterval
                    
                    if (previousCommittedHeight >= 0) {
                        val previousRoot = getSafeRootAtHeight(headerChain, chainHeight, previousCommittedHeight)
                        if (previousRoot != null) {
                            val fallbackResult = SpvP2P.queryName(nameHash, previousRoot, chainHeight, headerChain)
                            
                            if (fallbackResult is SpvP2P.NameQueryResult.Success) {
                                val proof = fallbackResult.proof
                                if (proof != null) {
                                    val parsedProof = Proof.parseProof(proof)
                                    if (parsedProof != null) {
                                        val verifyResult = Proof.verifyProof(previousRoot, nameHash, parsedProof)
                                        if (verifyResult.success && verifyResult.exists && verifyResult.data != null) {
                                            val records = parseResourceRecordsFromProof(verifyResult.data)
                                            if (records.isNotEmpty()) {
                                                return@withContext records
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return@withContext null
                }
                is SpvP2P.NameQueryResult.Error -> {
                }
            }
        } catch (e: Exception) {
        }
        
        null
    }
    
    /**
     * Get safe root for a peer's height (public function for ConnectionManager)
     * 
     * CRITICAL: For peer queries, always use a committed interval root (not current tip)
     * Even if mod >= 12, use the last committed interval to ensure the peer has indexed it
     * The current tip root might not be indexed yet by the peer's name tree index
     * 
     * hnsd uses pool->chain.tip->name_root, but that's hnsd's own safe root (which may be
     * from a committed interval if mod < 12). For peer queries, we need to ensure the peer
     * has the root, so we use the committed interval.
     */
    fun getSafeRootForPeer(headerChain: List<Header>, ourChainHeight: Int, peerHeight: Int): ByteArray? {
        if (headerChain.isEmpty()) {
            return null
        }
        
        // For peer queries, always use the last committed interval root
        // This ensures the peer has definitely indexed this root in its name tree
        val interval = Config.TREE_INTERVAL  // 36 blocks on mainnet
        val mod = peerHeight % interval
        
        
        // Always use the last committed interval (don't use current tip even if mod >= 12)
        // The current tip root might not be indexed yet by the peer
        val committedHeight = peerHeight - mod
        
        // If peerHeight is exactly on an interval boundary (mod=0), go back one interval
        // to ensure the peer has definitely indexed it
        val rootHeight = if (mod == 0) {
            val previousIntervalHeight = committedHeight - interval
            previousIntervalHeight
        } else {
            committedHeight
        }
        
        
        // Get root from the committed interval (guaranteed to be indexed by peer)
        return getSafeRootAtHeight(headerChain, ourChainHeight, rootHeight)
    }
    
    /**
     * Get safe root for name queries (EXACT MATCH to hnsd's hsk_chain_safe_root)
     * hnsd chain.c:180-209
     * The tree is committed on an interval (36 blocks on mainnet).
     * We use a root from a block that has enough proof-of-work on top of it.
     */
    private fun getSafeRoot(headerChain: List<Header>, chainHeight: Int, targetHeight: Int? = null): ByteArray? {
        // EXACT MATCH to hnsd chain.c:180-209 (hsk_chain_safe_root)
        // If targetHeight is provided, use it; otherwise calculate from chainHeight
        val heightToUse = targetHeight ?: chainHeight
        val interval = Config.TREE_INTERVAL  // 36 blocks on mainnet
        var mod = heightToUse % interval
        
        
        // If there's enough proof-of-work on top of the most recent root,
        // it should be safe to use it.
        // EXACT MATCH to hnsd chain.c:197-198
        if (mod >= 12) {
            mod = 0
        }
        
        val safeHeight = heightToUse - mod
        
        // hnsd does NOT subtract an interval - it uses the safe height directly
        // hnsd chain.c:200: uint32_t height = (uint32_t)chain->height - mod;
        // hnsd chain.c:202: hsk_header_t *prev = hsk_chain_get_by_height(chain, height);
        // hnsd chain.c:209: return prev->name_root;
        // CRITICAL: Pass actual chainHeight to getSafeRootAtHeight for firstHeaderHeight calculation
        return getSafeRootAtHeight(headerChain, chainHeight, safeHeight)
    }
    
    /**
     * Get safe root at a specific height (matching hnsd chain.c:202)
     * hsk_chain_get_by_height(chain, height) -> returns header at that height
     * Returns prev->name_root
     */
    fun getSafeRootAtHeight(headerChain: List<Header>, chainHeight: Int, targetHeight: Int): ByteArray? {
        if (headerChain.isEmpty()) {
            return null
        }
        
        // Find the header at targetHeight
        // headerChain is ordered from oldest to newest
        // headerChain[0] is at height (chainHeight - headerChain.size + 1)
        // headerChain[i] is at height (chainHeight - headerChain.size + 1 + i)
        val firstHeaderHeight = chainHeight - headerChain.size + 1
        
        val safeHeader = if (targetHeight >= firstHeaderHeight && targetHeight <= chainHeight) {
            // We have this header in memory
            val index = targetHeight - firstHeaderHeight
            if (index >= 0 && index < headerChain.size) {
                val header = headerChain[index]
                val rootHex = header.nameRoot.joinToString("") { "%02x".format(it) }
                header
            } else {
                null
            }
        } else {
            null
        }
        
        if (safeHeader == null) {
            return null
        }
        
        return safeHeader.nameRoot
    }
    
    private fun computeNameHash(name: String): ByteArray {
        // EXACT MATCH to hnsd hash.c:144-147 (hsk_hash_name)
        // hnsd does NOT normalize - it hashes the raw name string as-is
        // hsk_hash_name(name, hash) -> hsk_hash_sha3((uint8_t *)name, strlen(name), hash)
        // NOTE: This should be the TLD only (e.g., "conceal", not "website.conceal")
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        sha3_256.reset()
        sha3_256.update(nameBytes, 0, nameBytes.size)
        val hash = ByteArray(32)
        sha3_256.doFinal(hash, 0)
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return hash
    }
    
    /**
     * Parse resource records from proof data (extracted from name state)
     * The proof data is the resource data portion from hsk_parse_namestate
     */
    private fun parseResourceRecordsFromProof(proofData: ByteArray): List<com.acktarius.hnsgo.Record> {
        // Use ResourceDecoder to decode resource records from proof data
        // This matches hnsd's hsk_resource_decode (resource.c:387-447)
        return ResourceDecoder.decodeResource(proofData)
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
                                    com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_DNS_A, ip.toByteArray(Charsets.UTF_8))
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
                    }
                }
            } catch (e: Exception) {
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
        
        if (proof == null) {
            return false
        }
        
        if (nameHash.size != 32 || nameRoot.size != 32) {
            return false
        }
        
        try {
            val proofNodes = parseMerkleProof(proof)
            if (proofNodes.isEmpty()) {
                return false
            }
            
            
            val calculatedRoot = reconstructMerkleRoot(nameHash, records, proofNodes)
            
            if (calculatedRoot == null) {
                return false
            }
            
            val isValid = calculatedRoot.contentEquals(nameRoot)
            
            if (isValid) {
            } else {
            }
            
            return isValid
        } catch (e: Exception) {
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
                    return nodes
                }
            }
        } catch (e: Exception) {
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
    
    /**
     * Parse cached Handshake records from CBOR format
     * 
     * Handshake record types:
     * - 0: HSK_NS - Nameserver record
     * - 1: HSK_GLUE4 - IPv4 glue record
     * - 2: HSK_GLUE6 - IPv6 glue record
     * - 5: HSK_DS - DS record
     * - 6: HSK_TEXT - TXT record
     * 
     * Returns Handshake records (not DNS records) - these will be converted to DNS format later
     */
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
                    // Parse all Handshake record types (0=NS, 1=GLUE4, 2=GLUE6, 5=DS, 6=TXT)
                    when (type) {
                        com.acktarius.hnsgo.Config.HSK_DS -> records.add(com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_DS, dataBytes))
                        com.acktarius.hnsgo.Config.HSK_NS -> records.add(com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_NS, dataBytes))
                        com.acktarius.hnsgo.Config.HSK_GLUE4 -> records.add(com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_GLUE4, dataBytes))
                        com.acktarius.hnsgo.Config.HSK_GLUE6 -> records.add(com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_GLUE6, dataBytes))
                        com.acktarius.hnsgo.Config.HSK_TEXT -> records.add(com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_TEXT, dataBytes))
                        else -> {
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return records
    }
}

