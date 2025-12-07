package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.NSRecord
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record as DNSRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Recursive DNS Resolver for Handshake domains
 * 
 * Based on hnsd/src/rs.c - implements recursive resolution by:
 * 1. Querying blockchain for TLD NS records (via SpvClient)
 * 2. Following NS records to query nameservers for subdomain records
 * 3. Returning final answer to client
 * 
 * Unlike hnsd which uses libunbound, this is a simplified recursive resolver
 * that handles the Handshake-specific resolution flow.
 */
object RecursiveResolver {
    private const val DNS_PORT = 53
    private const val DNS_TIMEOUT_MS = 5000L
    private const val MAX_RECURSION_DEPTH = 10
    
    /**
     * Recursively resolve a Handshake domain name
     * 
     * Flow:
     * 1. Extract TLD and query blockchain for NS + GLUE records
     * 2. If subdomain query, query nameservers for subdomain records
     * 3. Return final answer
     * 
     * @param name Domain name (e.g., "website.conceal")
     * @param type DNS record type (Type.A, Type.AAAA, etc.)
     * @return DNS Message with answer, or null if not found
     */
    suspend fun resolve(
        name: String,
        type: Int
    ): org.xbill.DNS.Message? = withContext(Dispatchers.IO) {
        Log.d("HNSGo", "RecursiveResolver: Resolving '$name' (type: $type)")
        
        // Extract TLD
        val tld = ResourceRecord.extractTLD(name)
        val isTLDQuery = (name.lowercase().trimEnd('.') == tld.lowercase())
        
        // Step 1: Query blockchain for TLD resource records
        // Note: SpvClient.resolve() already returns DNS records (converted by NameResolver)
        val dnsRecords = SpvClient.resolve(name) // This already extracts TLD internally and converts to DNS
        if (dnsRecords == null || dnsRecords.isEmpty()) {
            Log.d("HNSGo", "RecursiveResolver: No blockchain records found for '$name'")
            return@withContext null
        }
        
        // Separate record types (using DNS record types: 2=NS, 1=A, 28=AAAA)
        val nsRecords: List<com.acktarius.hnsgo.Record> = dnsRecords.filter { it.type == 2 } // NS records
        val glueARecords: List<com.acktarius.hnsgo.Record> = dnsRecords.filter { it.type == 1 } // A records (GLUE)
        val glueAAAARecords: List<com.acktarius.hnsgo.Record> = dnsRecords.filter { it.type == 28 } // AAAA records (GLUE)
        
        // If this is a TLD query, return NS + GLUE records
        if (isTLDQuery) {
            Log.d("HNSGo", "RecursiveResolver: TLD query - returning NS + GLUE records")
            return@withContext buildTLDResponse(name, type, nsRecords, glueARecords, glueAAAARecords)
        }
        
        // Step 2: For subdomain queries, follow NS records
        if (nsRecords.isEmpty()) {
            Log.w("HNSGo", "RecursiveResolver: No NS records found for TLD '$tld'")
            return@withContext null
        }
        
        Log.d("HNSGo", "RecursiveResolver: Subdomain query - following ${nsRecords.size} NS records")
        
        // Query nameservers for subdomain
        // Note: nsRecords validated above but not needed - we use GLUE records directly
        val answer = queryNameservers(
            name,
            type,
            glueARecords,
            glueAAAARecords,
            depth = 0
        )
        
        if (answer != null) {
            Log.d("HNSGo", "RecursiveResolver: Successfully resolved '$name' from nameservers")
        } else {
            Log.w("HNSGo", "RecursiveResolver: Failed to resolve '$name' from nameservers")
        }
        
        return@withContext answer
    }
    
    /**
     * Build DNS response for TLD queries (NS + GLUE records)
     */
    private fun buildTLDResponse(
        name: String,
        queryType: Int,
        nsRecords: List<com.acktarius.hnsgo.Record>,
        glueARecords: List<com.acktarius.hnsgo.Record>,
        glueAAAARecords: List<com.acktarius.hnsgo.Record>
    ): org.xbill.DNS.Message {
        val queryName = Name(name, Name.root)
        val response = org.xbill.DNS.Message().apply {
            header.setFlag(Flags.QR.toInt())
            header.setFlag(Flags.AA.toInt()) // Authoritative answer
            addRecord(DNSRecord.newRecord(queryName, queryType, DClass.IN), Section.QUESTION)
        }
        
        // Add NS records to AUTHORITY section (TTL: 6 hours, matching hnsd)
        val ttl = Config.DNS_CACHE_TTL_SECONDS.toLong()
        nsRecords.forEach { rec ->
            val nameserver = String(rec.data, Charsets.UTF_8).trim()
            val nsName = Name(nameserver, Name.root)
            response.addRecord(NSRecord(queryName, DClass.IN, ttl, nsName), Section.AUTHORITY)
            Log.d("HNSGo", "RecursiveResolver: Added NS record: $nameserver")
        }
        
        // Add GLUE records (A/AAAA) to ADDITIONAL section
        // Match GLUE records to NS records by name (matching hnsd's hsk_resource_to_glue)
        // GLUE record format: "name\0ip" (name + null byte + IP string)
        nsRecords.forEach { nsRec ->
            val nameserver = String(nsRec.data, Charsets.UTF_8).trim()
            val nsName = Name(nameserver, Name.root)
            
            // Find matching GLUE4 record by name
            val matchingGlueA = glueARecords.find { glueRec ->
                val glueData = String(glueRec.data, Charsets.UTF_8)
                val nullIndex = glueData.indexOf('\u0000')
                if (nullIndex > 0) {
                    val glueName = glueData.substring(0, nullIndex).trim()
                    glueName.equals(nameserver, ignoreCase = true)
                } else {
                    false
                }
            }
            
            if (matchingGlueA != null) {
                val glueData = String(matchingGlueA.data, Charsets.UTF_8)
                val nullIndex = glueData.indexOf('\u0000')
                if (nullIndex > 0) {
                    val glueName = glueData.substring(0, nullIndex).trim()
                    val ip = glueData.substring(nullIndex + 1).trim()
                    try {
                        response.addRecord(ARecord(nsName, DClass.IN, ttl, InetAddress.getByName(ip)), Section.ADDITIONAL)
                        Log.d("HNSGo", "RecursiveResolver: Added GLUE A: $ip for $nameserver (matched by name: $glueName)")
                    } catch (e: Exception) {
                        Log.e("HNSGo", "RecursiveResolver: Error parsing GLUE A: $ip", e)
                    }
                }
            }
            
            // Find matching GLUE6 record by name (only if no A record found)
            if (matchingGlueA == null) {
                val matchingGlueAAAA = glueAAAARecords.find { glueRec ->
                    val glueData = String(glueRec.data, Charsets.UTF_8)
                    val nullIndex = glueData.indexOf('\u0000')
                    if (nullIndex > 0) {
                        val glueName = glueData.substring(0, nullIndex).trim()
                        glueName.equals(nameserver, ignoreCase = true)
                    } else {
                        false
                    }
                }
                
                if (matchingGlueAAAA != null) {
                    val glueData = String(matchingGlueAAAA.data, Charsets.UTF_8)
                    val nullIndex = glueData.indexOf('\u0000')
                    if (nullIndex > 0) {
                        val glueName = glueData.substring(0, nullIndex).trim()
                        val ip = glueData.substring(nullIndex + 1).trim()
                        try {
                            response.addRecord(AAAARecord(nsName, DClass.IN, ttl, InetAddress.getByName(ip)), Section.ADDITIONAL)
                            Log.d("HNSGo", "RecursiveResolver: Added GLUE AAAA: $ip for $nameserver (matched by name: $glueName)")
                        } catch (e: Exception) {
                            Log.e("HNSGo", "RecursiveResolver: Error parsing GLUE AAAA: $ip", e)
                        }
                    }
                }
            }
        }
        
        return response
    }
    
    /**
     * Query nameservers for subdomain records
     * 
     * @param name Subdomain name (e.g., "website.conceal")
     * @param type DNS record type
     * @param glueARecords GLUE A records (IPv4 addresses)
     * @param glueAAAARecords GLUE AAAA records (IPv6 addresses)
     * @param depth Recursion depth (to prevent infinite loops)
     * @return DNS Message with answer, or null if not found
     */
    private suspend fun queryNameservers(
        name: String,
        type: Int,
        glueARecords: List<com.acktarius.hnsgo.Record>,
        glueAAAARecords: List<com.acktarius.hnsgo.Record>,
        depth: Int
    ): org.xbill.DNS.Message? = withContext(Dispatchers.IO) {
        if (depth >= MAX_RECURSION_DEPTH) {
            Log.w("HNSGo", "RecursiveResolver: Max recursion depth reached")
            return@withContext null
        }
        
        // Build nameserver IP list from GLUE records
        // GLUE record format: "name\0ip" (name + null byte + IP string)
        // CRITICAL: Prioritize IPv4 over IPv6 (IPv4 is faster and more reliable)
        val ipv4Addresses = mutableListOf<String>()
        val ipv6Addresses = mutableListOf<String>()
        
        // Add IPv4 addresses first
        glueARecords.forEach { rec ->
            val glueData = String(rec.data, Charsets.UTF_8)
            val nullIndex = glueData.indexOf('\u0000')
            if (nullIndex > 0) {
                val ip = glueData.substring(nullIndex + 1).trim()
                ipv4Addresses.add(ip)
            } else {
                // Fallback: old format (just IP)
                val ip = glueData.trim()
                ipv4Addresses.add(ip)
            }
        }
        
        // Add IPv6 addresses (will try after IPv4)
        glueAAAARecords.forEach { rec ->
            val glueData = String(rec.data, Charsets.UTF_8)
            val nullIndex = glueData.indexOf('\u0000')
            if (nullIndex > 0) {
                val ip = glueData.substring(nullIndex + 1).trim()
                ipv6Addresses.add(ip)
            } else {
                // Fallback: old format (just IP)
                val ip = glueData.trim()
                ipv6Addresses.add(ip)
            }
        }
        
        if (ipv4Addresses.isEmpty() && ipv6Addresses.isEmpty()) {
            Log.w("HNSGo", "RecursiveResolver: No nameserver IPs available (no GLUE records)")
            return@withContext null
        }
        
        Log.d("HNSGo", "RecursiveResolver: Querying ${ipv4Addresses.size} IPv4 + ${ipv6Addresses.size} IPv6 nameservers for '$name'")
        
        // Try IPv4 nameservers first (faster, more reliable)
        for (ip in ipv4Addresses) {
            try {
                val answer = queryNameserver(name, type, ip)
                if (answer != null) {
                    val answers = answer.getSection(Section.ANSWER)
                    if (answers.isNotEmpty()) {
                        // Log record types in answer section
                        val recordTypes = answers.map { record ->
                            "${Type.string(record.type)}(${record.rdataToString()})"
                        }.joinToString(", ")
                        Log.d("HNSGo", "RecursiveResolver: Got answer from $ip: ${answers.size} records [${recordTypes}]")
                        return@withContext answer
                    }
                }
            } catch (e: Exception) {
                Log.w("HNSGo", "RecursiveResolver: Error querying IPv4 nameserver $ip: ${e.message}")
                continue
            }
        }
        
        // If IPv4 failed, try IPv6 nameservers (slower, but may work if IPv4 unavailable)
        if (ipv6Addresses.isNotEmpty()) {
            Log.d("HNSGo", "RecursiveResolver: IPv4 nameservers failed, trying ${ipv6Addresses.size} IPv6 nameservers")
            for (ip in ipv6Addresses) {
                try {
                    val answer = queryNameserver(name, type, ip)
                    if (answer != null) {
                        val answers = answer.getSection(Section.ANSWER)
                        if (answers.isNotEmpty()) {
                            val recordTypes = answers.map { record ->
                                "${Type.string(record.type)}(${record.rdataToString()})"
                            }.joinToString(", ")
                            Log.d("HNSGo", "RecursiveResolver: Got answer from IPv6 $ip: ${answers.size} records [${recordTypes}]")
                            return@withContext answer
                        }
                    }
                } catch (e: Exception) {
                    Log.w("HNSGo", "RecursiveResolver: Error querying IPv6 nameserver $ip: ${e.message}")
                    continue
                }
            }
        }
        
        Log.w("HNSGo", "RecursiveResolver: All nameservers failed for '$name' (${ipv4Addresses.size} IPv4 + ${ipv6Addresses.size} IPv6)")
        return@withContext null
    }
    
    /**
     * Query a single nameserver via UDP DNS
     * 
     * @param name Domain name to query
     * @param type DNS record type
     * @param ip Nameserver IP address (IPv4 or IPv6 - handled automatically)
     * @return DNS Message with answer, or null if failed
     */
    private suspend fun queryNameserver(
        name: String,
        type: Int,
        ip: String
    ): org.xbill.DNS.Message? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = DNS_TIMEOUT_MS.toInt()
            
            // Build DNS query
            val queryName = Name(name, Name.root)
            val query = org.xbill.DNS.Message.newQuery(
                DNSRecord.newRecord(queryName, type, DClass.IN)
            )
            
            val queryBytes = query.toWire()
            
            // Send query
            val address = InetAddress.getByName(ip)
            val socketAddress = InetSocketAddress(address, DNS_PORT)
            val packet = DatagramPacket(queryBytes, queryBytes.size, socketAddress)
            
            Log.d("HNSGo", "RecursiveResolver: Sending DNS query to $ip:$DNS_PORT for '$name'")
            socket.send(packet)
            
            // Receive response
            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            
            val responseBytes = responsePacket.data.sliceArray(0 until responsePacket.length)
            val response = org.xbill.DNS.Message(responseBytes)
            
            Log.d("HNSGo", "RecursiveResolver: Received response from $ip (rcode: ${response.rcode})")
            
            if (response.rcode == Rcode.NOERROR) {
                return@withContext response
            } else {
                Log.w("HNSGo", "RecursiveResolver: Nameserver returned rcode: ${response.rcode}")
                return@withContext null
            }
            
        } catch (e: SocketTimeoutException) {
            Log.w("HNSGo", "RecursiveResolver: Timeout querying nameserver $ip")
            return@withContext null
        } catch (e: Exception) {
            Log.e("HNSGo", "RecursiveResolver: Error querying nameserver $ip", e)
            return@withContext null
        } finally {
            socket?.close()
        }
    }
}

