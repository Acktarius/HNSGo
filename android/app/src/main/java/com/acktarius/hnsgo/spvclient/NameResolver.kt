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
            val tldHashHex = tldHash.joinToString("") { "%02x".format(it) }
            android.util.Log.d("HNSGo", "NameResolver: Querying blockchain for TLD '$tld' (hash: $tldHashHex)")
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
        
        // EXACT MATCH to hnsd: Use safe root from our own chain (hsk_chain_safe_root)
        // hnsd pool.c:521: const uint8_t *root = hsk_chain_safe_root(&pool->chain);
        // hnsd uses ONE root, calculated from its own chain height
        // BUT: Peers might be slightly behind (1-2 blocks), so we should use a root that's at least 1 interval old
        // This ensures peers that are slightly behind can still answer
        // Start with a root that's 1 interval old, then fall back to tip root if needed
        val interval = Config.TREE_INTERVAL
        val firstHeaderHeight = chainHeight - headerChain.size + 1
        
        // Calculate initial root from 1 interval back to ensure peers can answer
        val initialHeight = maxOf(firstHeaderHeight, chainHeight - interval)
        // CRITICAL: Pass actual chainHeight to getSafeRoot, not initialHeight
        // getSafeRoot needs chainHeight to calculate firstHeaderHeight correctly
        val safeRoot = getSafeRoot(headerChain, chainHeight, initialHeight)
        if (safeRoot == null) {
            android.util.Log.w("HNSGo", "NameResolver: Could not determine safe root")
            return@withContext null
        }
        
        // VERIFICATION: Log what we're sending
        val safeRootHex = safeRoot.joinToString("") { "%02x".format(it) }
        val nameHashHex = nameHash.joinToString("") { "%02x".format(it) }
        android.util.Log.w("HNSGo", "NameResolver: ========== VERIFICATION START ==========")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Querying P2P for TLD '$name'")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Network: MAINNET (magic: 0x${String.format("%08x", Config.MAGIC_MAINNET)})")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Chain height: $chainHeight")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - TLD name hash: $nameHashHex")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Expected hash (from shakeshift.com): 9881230ee09e526ce8df5b263529207286e94a7a3fa5fecf328f3e2d869fb79e")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Hash match: ${nameHashHex == "9881230ee09e526ce8df5b263529207286e94a7a3fa5fecf328f3e2d869fb79e"}")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Safe root: $safeRootHex")
        
        // VERIFICATION: Calculate safeHeight using same logic as getSafeRoot
        // Reuse initialHeight from above (line 171)
        var mod = initialHeight % interval
        if (mod >= 12) mod = 0
        val safeHeight = initialHeight - mod
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Safe root from height: $safeHeight (initialHeight: $initialHeight, chainHeight: $chainHeight)")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Domain registered at block 276,466 (from shakeshift.com)")
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Our height ($chainHeight) is ${chainHeight - 276466} blocks ahead of registration")
        
        // VERIFICATION: Verify the safeRoot matches the header at safeHeight
        android.util.Log.w("HNSGo", "NameResolver: VERIFICATION - Header chain range: $firstHeaderHeight to $chainHeight (${headerChain.size} headers)")
        if (safeHeight >= firstHeaderHeight && safeHeight <= chainHeight) {
            val index = safeHeight - firstHeaderHeight
            if (index >= 0 && index < headerChain.size) {
                val headerAtSafeHeight = headerChain[index]
                val headerNameRootHex = headerAtSafeHeight.nameRoot.joinToString("") { "%02x".format(it) }
                if (headerNameRootHex != safeRootHex) {
                    android.util.Log.e("HNSGo", "NameResolver: VERIFICATION FAILED - Safe root mismatch!")
                    android.util.Log.e("HNSGo", "NameResolver: VERIFICATION - Header at height $safeHeight has nameRoot: $headerNameRootHex")
                    android.util.Log.e("HNSGo", "NameResolver: VERIFICATION - But we're using safeRoot: $safeRootHex")
                } else {
                    android.util.Log.w("HNSGo", "NameResolver: VERIFICATION PASSED - Safe root matches header at height $safeHeight")
                }
            } else {
                android.util.Log.e("HNSGo", "NameResolver: VERIFICATION - Index out of bounds: index=$index, size=${headerChain.size}")
            }
        } else {
            android.util.Log.e("HNSGo", "NameResolver: VERIFICATION - Safe height $safeHeight is outside header chain range ($firstHeaderHeight to $chainHeight)")
        }
        android.util.Log.w("HNSGo", "NameResolver: ========== VERIFICATION END ==========")
        
        // CRITICAL: Try multiple roots to handle peers that are slightly behind
        // Strategy: Start with a root that's 1 interval old (most likely to work with peers),
        // then try tip root, then even older roots
        val rootsToTry = mutableListOf<Pair<ByteArray, Int>>()
        
        // 1. Add the initial root (1 interval old, calculated above)
        rootsToTry.add(Pair(safeRoot, safeHeight))
        
        // 2. Also try tip root (as hnsd does) - peers at same height will have this
        val tipRoot = getSafeRoot(headerChain, chainHeight, null)
        if (tipRoot != null) {
            var tipMod = chainHeight % interval
            if (tipMod >= 12) tipMod = 0
            val tipSafeHeight = chainHeight - tipMod
            if (tipSafeHeight != safeHeight) {
                rootsToTry.add(Pair(tipRoot, tipSafeHeight))
                android.util.Log.w("HNSGo", "NameResolver: Will also try tip root from height $tipSafeHeight")
            }
        }
        
        // 3. Try roots from 2-3 intervals back as fallback
        for (i in 2..3) {
            val olderHeight = safeHeight - (interval * i)
            if (olderHeight >= firstHeaderHeight && olderHeight <= chainHeight && olderHeight >= 0) {
                val index = olderHeight - firstHeaderHeight
                if (index >= 0 && index < headerChain.size) {
                    val olderHeader = headerChain[index]
                    rootsToTry.add(Pair(olderHeader.nameRoot, olderHeight))
                    android.util.Log.w("HNSGo", "NameResolver: Will also try root from height $olderHeight as fallback")
                }
            }
        }
        
        try {
            // Query with safe root (matching hnsd pool.c:521 - uses ONE root)
            // hnsd uses its own chain's safe root and does NOT adjust for peer height
            // But we'll try multiple roots if the first one fails (peers might be behind)
            // CRITICAL: Try each root with a few peers first, then move to next root if it fails
            // This allows us to quickly test if a root works, rather than trying all 60 peers with one root
            var nameQueryResult: SpvP2P.NameQueryResult? = null
            for ((root, rootHeight) in rootsToTry) {
                val rootHex = root.joinToString("") { "%02x".format(it) }
                android.util.Log.w("HNSGo", "NameResolver: Trying root from height $rootHeight: $rootHex")
                
                // Try this root with a limited number of peers first (5 peers)
                // If it fails, try the next root rather than exhausting all 60 peers
                // Pass headerChain so queryNameFromPeer can adjust root based on peer height
                nameQueryResult = SpvP2P.queryName(nameHash, root, chainHeight, headerChain, maxPeers = 5)
                
                when (nameQueryResult) {
                    is SpvP2P.NameQueryResult.Success -> {
                        android.util.Log.w("HNSGo", "NameResolver: Success with root from height $rootHeight")
                        break
                    }
                    is SpvP2P.NameQueryResult.NotFound -> {
                        android.util.Log.w("HNSGo", "NameResolver: Notfound with root from height $rootHeight after trying 5 peers, trying next root...")
                        continue
                    }
                    is SpvP2P.NameQueryResult.Error -> {
                        android.util.Log.w("HNSGo", "NameResolver: Error with root from height $rootHeight, trying next root...")
                        continue
                    }
                }
            }
            
        // If all roots failed with limited peers, try the first (tip) root with all peers as final attempt
        if (nameQueryResult !is SpvP2P.NameQueryResult.Success && rootsToTry.isNotEmpty()) {
            val (tipRoot, tipHeight) = rootsToTry[0]
            android.util.Log.w("HNSGo", "NameResolver: All roots failed with limited peers, trying tip root (height $tipHeight) with all peers as final attempt...")
            nameQueryResult = SpvP2P.queryName(nameHash, tipRoot, chainHeight, headerChain)
        }
            
            val finalResult = nameQueryResult ?: SpvP2P.NameQueryResult.NotFound
                
            when (finalResult) {
                is SpvP2P.NameQueryResult.Success -> {
                    val proof = finalResult.proof
                    android.util.Log.d("HNSGo", "NameResolver: P2P query success (proof: ${proof != null})")
                    
                    if (proof == null) {
                        android.util.Log.w("HNSGo", "NameResolver: No proof in response")
                        return@withContext null
                    }
                    
                    // Find which root succeeded (use the last one we tried, or safeRoot as fallback)
                    var verifiedRoot = safeRoot
                    for ((root, _) in rootsToTry.reversed()) {
                        // Try to verify with this root
                        verifiedRoot = root
                        break
                    }
                    
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
    private fun getSafeRootAtHeight(headerChain: List<Header>, chainHeight: Int, targetHeight: Int): ByteArray? {
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
                headerChain[index]
            } else {
                // This shouldn't happen if calculation is correct
                android.util.Log.w("HNSGo", "NameResolver: Index calculation error (index=$index, size=${headerChain.size})")
                null
            }
        } else {
            // Target height is beyond what we have - this shouldn't happen for safe root
            android.util.Log.w("HNSGo", "NameResolver: Target height $targetHeight is beyond available headers (first: $firstHeaderHeight, last: $chainHeight)")
            null
        }
        
        if (safeHeader == null) {
            android.util.Log.e("HNSGo", "NameResolver: getSafeRootAtHeight - safeHeader is null!")
            return null
        }
        
        val rootHex = safeHeader.nameRoot.joinToString("") { "%02x".format(it) }
        android.util.Log.d("HNSGo", "NameResolver: Using safe root from height $targetHeight (chain height: $chainHeight, root: $rootHex)")
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
        // The proof data is the resource data extracted from name state
        // It should contain resource records in Handshake format
        // For now, try parsing as resource records
        return parseResourceRecords(listOf(proofData))
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

