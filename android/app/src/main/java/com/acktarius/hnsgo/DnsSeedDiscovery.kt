package com.acktarius.hnsgo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import android.util.Log
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

/**
 * DNS seed discovery for Handshake peer discovery
 * 
 * Implements the primary peer discovery method as defined in .cursorrules:
 * - Query DNS seeds first (from Config.DNS_SEEDS): aoihakkila.com, easyhandshake.com
 * - These DNS seeds return A/AAAA records with peer IP addresses
 * - Format: IP:12038 (mainnet port)
 * 
 * Reference: Similar to Bitcoin's DNS seed discovery
 */
object DnsSeedDiscovery {
    // DNS seed domains (primary method) - loaded from Config.DNS_SEEDS
    private val DNS_SEEDS = Config.DNS_SEEDS
    
    // Handshake mainnet port (from Config)
    private const val MAINNET_PORT = Config.MAINNET_PORT
    
    /**
     * Discover peers from DNS seeds
     * Queries each DNS seed for A and AAAA records
     * Returns list of peer addresses in format "IP:port"
     */
    suspend fun discoverPeersFromDnsSeeds(): List<String> = withContext(Dispatchers.IO) {
        val discoveredPeers = mutableSetOf<String>()
        
        // Query all DNS seeds in parallel for faster discovery
        // Each seed query runs concurrently on Dispatchers.IO
        val seedResults = coroutineScope {
            DNS_SEEDS.map { seed ->
                async {
                    try {
                        queryDnsSeed(seed)
                    } catch (e: Exception) {
                        emptyList<String>()
                    }
                }
            }.awaitAll()
        }
        
        // Collect all discovered peers
        for (peers in seedResults) {
            if (peers.isNotEmpty()) {
                discoveredPeers.addAll(peers)
            }
        }
        
        val peerList = discoveredPeers.toList()
        return@withContext peerList
    }
    
    /**
     * Query a single DNS seed for peer addresses
     * Looks for A (IPv4) and AAAA (IPv6) records
     * Also tries TXT records which might contain peer IPs
     */
    private suspend fun queryDnsSeed(seedDomain: String): List<String> = withContext(Dispatchers.IO) {
        val peers = mutableListOf<String>()
        
        try {
            // Try multiple DNS resolvers for reliability
            val dnsServers = listOf(
                "9.9.9.9",      // Quad9 DNS
                "1.1.1.1",      // Cloudflare DNS
                null             // System default (try last)
            )
            
            var aRecords = emptyList<ARecord>()
            var aaaaRecords = emptyList<AAAARecord>()
            var txtRecords = emptyList<TXTRecord>()
            
            // Try each DNS server until one works
            for (dnsServer in dnsServers) {
                try {
                    val resolver = if (dnsServer != null) {
                        SimpleResolver(dnsServer).apply {
                            timeout = java.time.Duration.ofSeconds(5) // 5 second timeout per query
                        }
                    } else {
                        SimpleResolver().apply {
                            timeout = java.time.Duration.ofSeconds(5) // 5 second timeout per query
                        }
                    }
                    
                    // Query for A records (IPv4) with timeout
                    val aLookup = Lookup(seedDomain, Type.A)
                    aLookup.setResolver(resolver)
                    aLookup.run()
                    
                    if (aLookup.result == Lookup.SUCCESSFUL) {
                        aRecords = aLookup.answers?.filterIsInstance<ARecord>() ?: emptyList()
                        
                        // Also query AAAA and TXT with the same resolver
                        val aaaaLookup = Lookup(seedDomain, Type.AAAA)
                        aaaaLookup.setResolver(resolver)
                        aaaaLookup.run()
                        if (aaaaLookup.result == Lookup.SUCCESSFUL) {
                            aaaaRecords = aaaaLookup.answers?.filterIsInstance<AAAARecord>() ?: emptyList()
                        }
                        
                        val txtLookup = Lookup(seedDomain, Type.TXT)
                        txtLookup.setResolver(resolver)
                        txtLookup.run()
                        if (txtLookup.result == Lookup.SUCCESSFUL) {
                            txtRecords = txtLookup.answers?.filterIsInstance<TXTRecord>() ?: emptyList()
                        }
                        
                        // Success! Break out of loop
                        break
                    } else {
                    }
                } catch (e: Exception) {
                    // Continue to next DNS server
                }
            }
            
            // Convert A records to peer addresses
            for (record in aRecords) {
                try {
                    val address = record.address
                    if (address != null) {
                        val ip = address.hostAddress
                        if (ip != null && ip.isNotBlank()) {
                            peers.add("$ip:$MAINNET_PORT")
                        }
                    }
                } catch (e: Exception) {
                }
            }
            
            // Convert AAAA records to peer addresses
            for (record in aaaaRecords) {
                try {
                    val address = record.address
                    if (address != null) {
                        val ip = address.hostAddress
                        if (ip != null && ip.isNotBlank()) {
                            peers.add("$ip:$MAINNET_PORT")
                        }
                    }
                } catch (e: Exception) {
                }
            }
            
            // Parse TXT records for peer IPs (format might be "IP:port" or just "IP")
            for (record in txtRecords) {
                try {
                    val txtStrings = record.strings
                    if (txtStrings != null) {
                        for (txt in txtStrings) {
                            try {
                                val txtStr = txt.toString()
                                if (txtStr.isNotBlank()) {
                                    // Try to parse as IP:port or just IP
                                    if (txtStr.contains(":") && isValidPeerAddress(txtStr)) {
                                        peers.add(txtStr)
                                    } else if (isValidIP(txtStr)) {
                                        peers.add("$txtStr:$MAINNET_PORT")
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            
            if (peers.isEmpty()) {
            }
            
        } catch (e: Exception) {
        }
        
        return@withContext peers
    }
    
    /**
     * Check if a string is a valid IP address
     */
    private fun isValidIP(ip: String): Boolean {
        return try {
            InetAddress.getByName(ip) != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate that a peer address is properly formatted
     */
    fun isValidPeerAddress(address: String): Boolean {
        return try {
            val parts = address.split(":")
            if (parts.size != 2) return false
            
            val ip = parts[0]
            val port = parts[1].toInt()
            
            // Validate IP address
            InetAddress.getByName(ip) != null && port > 0 && port <= 65535
        } catch (e: Exception) {
            false
        }
    }
}

