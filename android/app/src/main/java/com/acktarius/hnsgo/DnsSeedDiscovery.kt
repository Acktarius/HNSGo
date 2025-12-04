package com.acktarius.hnsgo

import kotlinx.coroutines.Dispatchers
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
        
        Log.d("DnsSeedDiscovery", "Starting DNS seed discovery from ${DNS_SEEDS.size} seeds")
        
        for (seed in DNS_SEEDS) {
            try {
                Log.d("DnsSeedDiscovery", "Querying DNS seed: $seed")
                val peers = queryDnsSeed(seed)
                
                if (peers.isNotEmpty()) {
                    Log.d("DnsSeedDiscovery", "Found ${peers.size} peers from $seed")
                    discoveredPeers.addAll(peers)
                } else {
                    Log.w("DnsSeedDiscovery", "No peers found from DNS seed: $seed")
                }
            } catch (e: Exception) {
                Log.w("DnsSeedDiscovery", "Error querying DNS seed $seed: ${e.message}")
                // Continue to next seed
            }
        }
        
        val peerList = discoveredPeers.toList()
        Log.d("DnsSeedDiscovery", "DNS seed discovery complete: found ${peerList.size} unique peers")
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
                        Log.d("DnsSeedDiscovery", "Trying DNS server: $dnsServer for $seedDomain")
                        SimpleResolver(dnsServer).apply {
                            timeout = java.time.Duration.ofSeconds(5) // 5 second timeout per query
                        }
                    } else {
                        Log.d("DnsSeedDiscovery", "Trying system DNS resolver for $seedDomain")
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
                        Log.d("DnsSeedDiscovery", "Found ${aRecords.size} A records for $seedDomain using ${dnsServer ?: "system"}")
                        
                        // Also query AAAA and TXT with the same resolver
                        val aaaaLookup = Lookup(seedDomain, Type.AAAA)
                        aaaaLookup.setResolver(resolver)
                        aaaaLookup.run()
                        if (aaaaLookup.result == Lookup.SUCCESSFUL) {
                            aaaaRecords = aaaaLookup.answers?.filterIsInstance<AAAARecord>() ?: emptyList()
                            Log.d("DnsSeedDiscovery", "Found ${aaaaRecords.size} AAAA records for $seedDomain")
                        }
                        
                        val txtLookup = Lookup(seedDomain, Type.TXT)
                        txtLookup.setResolver(resolver)
                        txtLookup.run()
                        if (txtLookup.result == Lookup.SUCCESSFUL) {
                            txtRecords = txtLookup.answers?.filterIsInstance<TXTRecord>() ?: emptyList()
                            Log.d("DnsSeedDiscovery", "Found ${txtRecords.size} TXT records for $seedDomain")
                        }
                        
                        // Success! Break out of loop
                        break
                    } else {
                        Log.d("DnsSeedDiscovery", "DNS lookup failed for $seedDomain using ${dnsServer ?: "system"}: result=${aLookup.result}")
                    }
                } catch (e: Exception) {
                    Log.d("DnsSeedDiscovery", "Error querying DNS server ${dnsServer ?: "system"} for $seedDomain: ${e.message}")
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
                            Log.d("DnsSeedDiscovery", "Found IPv4 peer: $ip:$MAINNET_PORT")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DnsSeedDiscovery", "Error processing A record: ${e.message}")
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
                            Log.d("DnsSeedDiscovery", "Found IPv6 peer: $ip:$MAINNET_PORT")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DnsSeedDiscovery", "Error processing AAAA record: ${e.message}")
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
                                    Log.d("DnsSeedDiscovery", "TXT record: $txtStr")
                                    // Try to parse as IP:port or just IP
                                    if (txtStr.contains(":") && isValidPeerAddress(txtStr)) {
                                        peers.add(txtStr)
                                        Log.d("DnsSeedDiscovery", "Found peer from TXT: $txtStr")
                                    } else if (isValidIP(txtStr)) {
                                        peers.add("$txtStr:$MAINNET_PORT")
                                        Log.d("DnsSeedDiscovery", "Found IP from TXT: $txtStr")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("DnsSeedDiscovery", "Error processing TXT string: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DnsSeedDiscovery", "Error processing TXT record: ${e.message}")
                }
            }
            
            if (peers.isEmpty()) {
                Log.w("DnsSeedDiscovery", "No peers found from $seedDomain (A: ${aRecords.size}, AAAA: ${aaaaRecords.size}, TXT: ${txtRecords.size})")
                Log.w("DnsSeedDiscovery", "NOTE: $seedDomain may not be a DNS seed server, or may need to be queried differently")
            }
            
        } catch (e: Exception) {
            Log.e("DnsSeedDiscovery", "Error querying DNS seed $seedDomain", e)
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

