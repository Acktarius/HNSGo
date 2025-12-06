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
        android.util.Log.d("HNSGo", "NameResolver: Resolving Handshake domain: $name")
        
        val tld = ResourceRecord.extractTLD(name)
        android.util.Log.d("HNSGo", "NameResolver: Resolving domain '$name' -> TLD: '$tld'")
        
        // Check blacklist (matching hnsd ns.c:475-503)
        // Blacklisted TLDs are reserved for other naming systems and should return NXDOMAIN
        if (Config.BLACKLISTED_TLDS.contains(tld.lowercase())) {
            android.util.Log.d("HNSGo", "NameResolver: TLD '$tld' is blacklisted (reserved for other naming systems)")
            return@withContext null
        }
        
        val minRequiredHeight = Config.CHECKPOINT_HEIGHT + 1000
        if (chainHeight < minRequiredHeight) {
            android.util.Log.w("HNSGo", "NameResolver: Not enough headers synced (height: $chainHeight, required: $minRequiredHeight)")
        }
        
        val cached = CacheManager.get(tld, 1)
        if (cached != null) {
            android.util.Log.d("HNSGo", "NameResolver: Found cached record for TLD '$tld'")
            // Parse cached Handshake records and convert to DNS format (matching fresh query behavior)
            val cachedHandshakeRecords = parseRecords(cached)
            if (cachedHandshakeRecords.isNotEmpty()) {
                val cachedDnsRecords = ResourceRecord.convertHandshakeRecordsToDNS(cachedHandshakeRecords)
                android.util.Log.d("HNSGo", "NameResolver: Retrieved ${cachedDnsRecords.size} DNS records from cache for '$name'")
                return@withContext cachedDnsRecords
            }
        }

        if (chainHeight >= minRequiredHeight) {
            val tldHash = computeNameHash(tld)
            val tldHashHex = tldHash.joinToString("") { "%02x".format(it) }
            android.util.Log.w("HNSGo", "NameResolver: ========== QUERYING BLOCKCHAIN ==========")
            android.util.Log.w("HNSGo", "NameResolver: Full domain: '$name'")
            android.util.Log.w("HNSGo", "NameResolver: Extracted TLD: '$tld'")
            android.util.Log.w("HNSGo", "NameResolver: TLD hash (SHA3-256): $tldHashHex")
            android.util.Log.w("HNSGo", "NameResolver: ======================================")
            android.util.Log.d("HNSGo", "NameResolver: Querying blockchain for TLD '$tld' (hash: $tldHashHex)")
            val blockchainRecords = resolveFromBlockchain(tld, tldHash, headerChain, chainHeight)
            if (blockchainRecords != null && blockchainRecords.isNotEmpty()) {
                android.util.Log.d("HNSGo", "NameResolver: Successfully resolved TLD '$tld' from blockchain: ${blockchainRecords.size} records")
                val dnsRecords = ResourceRecord.convertHandshakeRecordsToDNS(blockchainRecords)
                if (dnsRecords.isNotEmpty()) {
                    android.util.Log.d("HNSGo", "NameResolver: Converted to ${dnsRecords.size} DNS records for '$name'")
                    val proofData = convertRecordsToProof(blockchainRecords, tld)
                    CacheManager.put(tld, 1, proofData, Config.DNS_CACHE_TTL_SECONDS)
                    android.util.Log.d("HNSGo", "NameResolver: Cached TLD '$tld' for ${Config.DNS_CACHE_TTL_SECONDS} seconds (6 hours)")
                    return@withContext dnsRecords
                }
            }
        } else {
            android.util.Log.w("HNSGo", "NameResolver: Skipping blockchain resolution - not enough headers synced yet (height: $chainHeight)")
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
        
        // Log last 10 headers for debugging
        val firstHeaderHeight = chainHeight - headerChain.size + 1
        val lastHeaders = headerChain.takeLast(10)
        android.util.Log.w("HNSGo", "NameResolver: Last 10 headers in chain:")
        lastHeaders.forEachIndexed { index, header ->
            val headerHeight = firstHeaderHeight + (headerChain.size - lastHeaders.size + index)
            val rootHex = header.nameRoot.joinToString("") { "%02x".format(it) }
        }
        
        // EXACT MATCH to hnsd's hsk_chain_safe_root (chain.c:180-209)
        // hnsd pool.c:521: const uint8_t *root = hsk_chain_safe_root(&pool->chain);
        val interval = Config.TREE_INTERVAL
        var mod = chainHeight % interval
        android.util.Log.w("HNSGo", "NameResolver: chainHeight=$chainHeight, mod=$mod")
        
        // If there's enough proof-of-work on top of the most recent root, it should be safe to use it
        // EXACT MATCH to hnsd chain.c:197-198
        if (mod >= 12) {
            mod = 0
            android.util.Log.w("HNSGo", "NameResolver: mod >= 12, setting mod=0")
        }
        
        // EXACT MATCH to hnsd chain.c:200: uint32_t height = (uint32_t)chain->height - mod;
        val rootHeight = chainHeight - mod
        android.util.Log.w("HNSGo", "NameResolver: Calculated rootHeight=$rootHeight (chainHeight=$chainHeight - mod=$mod)")
        
        // Check if tip root is different from committed interval root
        // If root changed at tip, peers may have indexed the new root
        val tipRoot = latestHeader.nameRoot
        val committedRoot = getSafeRootAtHeight(headerChain, chainHeight, rootHeight)
        
        val tipRootHex = tipRoot.joinToString("") { "%02x".format(it) }
        val committedRootHex = committedRoot?.joinToString("") { "%02x".format(it) } ?: "null"
        android.util.Log.w("HNSGo", "NameResolver: Tip root (height $chainHeight): $tipRootHex")
        android.util.Log.w("HNSGo", "NameResolver: Committed root (height $rootHeight): $committedRootHex")
        
        // If tip root is different from committed root, use tip root (peers have indexed it)
        val finalRootHeight = if (committedRoot != null && !tipRoot.contentEquals(committedRoot)) {
            android.util.Log.w("HNSGo", "NameResolver: Root changed at tip, using tip root (height $chainHeight)")
            chainHeight
        } else {
            rootHeight
        }
        
        // Verify header chain has the header at finalRootHeight
        if (finalRootHeight < firstHeaderHeight || finalRootHeight > chainHeight) {
            android.util.Log.e("HNSGo", "NameResolver: Header chain missing header at height $finalRootHeight (range: $firstHeaderHeight to $chainHeight, size: ${headerChain.size})")
            return@withContext null
        }
        
        val safeRoot = getSafeRootAtHeight(headerChain, chainHeight, finalRootHeight)
        if (safeRoot == null) {
            android.util.Log.w("HNSGo", "NameResolver: Could not determine safe root")
            return@withContext null
        }
        
        val rootHex = safeRoot.joinToString("") { "%02x".format(it) }
        android.util.Log.w("HNSGo", "NameResolver: Using root from height $finalRootHeight (chainHeight=$chainHeight, mod=$mod, headerRange=$firstHeaderHeight-$chainHeight): $rootHex")
        
        try {
            // Query with safe root - ConnectionManager will handshake first, get peer height,
            // then adjust root based on peer's height before sending query
            val nameQueryResult = SpvP2P.queryName(nameHash, safeRoot, chainHeight, headerChain)
            
            val finalResult = nameQueryResult
                
            when (finalResult) {
                is SpvP2P.NameQueryResult.Success -> {
                    val proof = finalResult.proof
                    android.util.Log.d("HNSGo", "NameResolver: P2P query success (proof: ${proof != null})")
                    
                    if (proof == null) {
                        android.util.Log.w("HNSGo", "NameResolver: No proof in response")
                        return@withContext null
                    }
                    
                    // Use safe root for verification
                    val verifiedRoot = safeRoot
                    
                    // Use Proof.kt to verify proof (matching hnsd's hsk_proof_verify)
                    val parsedProof = Proof.parseProof(proof)
                    if (parsedProof != null) {
                        android.util.Log.d("HNSGo", "NameResolver: Parsed proof (type: ${parsedProof.type}, depth: ${parsedProof.depth}, nodes: ${parsedProof.nodes.size})")
                        val verifyResult = Proof.verifyProof(verifiedRoot, nameHash, parsedProof)
                        
                        if (verifyResult.success && verifyResult.exists && verifyResult.data != null) {
                            // Parse resource records from proof data (extracted from name state)
                            val records = parseResourceRecordsFromProof(verifyResult.data)
                            if (records.isNotEmpty()) {
                                android.util.Log.d("HNSGo", "NameResolver: Successfully resolved $name from blockchain: ${records.size} records")
                                return@withContext records
                            } else {
                                android.util.Log.w("HNSGo", "NameResolver: No resource records found in proof data")
                            }
                        } else if (verifyResult.success && !verifyResult.exists) {
                            android.util.Log.d("HNSGo", "NameResolver: Proof verified but domain does not exist (DEADEND)")
                            return@withContext null
                        } else {
                            android.util.Log.w("HNSGo", "NameResolver: Proof verification failed (error code: ${verifyResult.errorCode})")
                        }
                    } else {
                        android.util.Log.w("HNSGo", "NameResolver: Failed to parse proof")
                    }
                }
                is SpvP2P.NameQueryResult.NotFound -> {
                    // Bounded fallback: try previous committed interval root (max 2 attempts)
                    android.util.Log.d("HNSGo", "NameResolver: notfound, trying previous interval root")
                    val treeInterval = Config.TREE_INTERVAL
                    val currentMod = chainHeight % treeInterval
                    val currentCommittedHeight = chainHeight - currentMod
                    val previousCommittedHeight = currentCommittedHeight - treeInterval
                    
                    if (previousCommittedHeight >= 0) {
                        val previousRoot = getSafeRootAtHeight(headerChain, chainHeight, previousCommittedHeight)
                        if (previousRoot != null) {
                            android.util.Log.d("HNSGo", "NameResolver: Trying previous interval (height: $previousCommittedHeight)")
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
                                                android.util.Log.d("HNSGo", "NameResolver: Resolved with previous interval root")
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
                    android.util.Log.w("HNSGo", "NameResolver: Error querying name from P2P peers")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "NameResolver: Exception during P2P name query", e)
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
        
        android.util.Log.d("HNSGo", "NameResolver: getSafeRootForPeer - peerHeight=$peerHeight, mod=$mod")
        
        // Always use the last committed interval (don't use current tip even if mod >= 12)
        // The current tip root might not be indexed yet by the peer
        val committedHeight = peerHeight - mod
        
        // If peerHeight is exactly on an interval boundary (mod=0), go back one interval
        // to ensure the peer has definitely indexed it
        val rootHeight = if (mod == 0) {
            val previousIntervalHeight = committedHeight - interval
            android.util.Log.d("HNSGo", "NameResolver: getSafeRootForPeer - peerHeight is on interval boundary, using previous interval: $previousIntervalHeight")
            previousIntervalHeight
        } else {
            committedHeight
        }
        
        android.util.Log.d("HNSGo", "NameResolver: getSafeRootForPeer - calculated rootHeight=$rootHeight (using committed interval root for peer queries)")
        
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
        
        android.util.Log.d("HNSGo", "NameResolver: getSafeRoot - chainHeight=$chainHeight, targetHeight=$targetHeight, heightToUse=$heightToUse, interval=$interval, mod=$mod")
        
        // If there's enough proof-of-work on top of the most recent root,
        // it should be safe to use it.
        // EXACT MATCH to hnsd chain.c:197-198
        if (mod >= 12) {
            mod = 0
            android.util.Log.d("HNSGo", "NameResolver: getSafeRoot - mod >= 12, setting mod=0")
        }
        
        val safeHeight = heightToUse - mod
        android.util.Log.d("HNSGo", "NameResolver: getSafeRoot - calculated safeHeight=$safeHeight (heightToUse=$heightToUse - mod=$mod)")
        
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
                android.util.Log.d("HNSGo", "NameResolver: Using root from header at height $targetHeight: $rootHex")
                header
            } else {
                android.util.Log.w("HNSGo", "NameResolver: Index calculation error (index=$index, size=${headerChain.size})")
                null
            }
        } else {
            android.util.Log.w("HNSGo", "NameResolver: Target height $targetHeight is beyond available headers (first: $firstHeaderHeight, last: $chainHeight)")
            null
        }
        
        if (safeHeader == null) {
            android.util.Log.e("HNSGo", "NameResolver: getSafeRootAtHeight - safeHeader is null!")
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
        android.util.Log.d("HNSGo", "NameResolver: computeNameHash - Hashing name: '$name' (${nameBytes.size} bytes, UTF-8)")
        sha3_256.reset()
        sha3_256.update(nameBytes, 0, nameBytes.size)
        val hash = ByteArray(32)
        sha3_256.doFinal(hash, 0)
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        android.util.Log.d("HNSGo", "NameResolver: computeNameHash - Computed SHA3-256 hash for '$name': $hashHex")
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
                            android.util.Log.w("HNSGo", "NameResolver: Unknown cached record type: $type")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HNSGo", "NameResolver: Error parsing cached records", e)
            }
        }
        return records
    }
}

