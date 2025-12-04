package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
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
        val blockchainRecords = SpvClient.resolve(name) // This already extracts TLD internally
        if (blockchainRecords == null || blockchainRecords.isEmpty()) {
            Log.d("HNSGo", "RecursiveResolver: No blockchain records found for '$name'")
            return@withContext null
        }
        
        // Separate record types
        val nsRecords: List<com.acktarius.hnsgo.Record> = blockchainRecords.filter { it.type == 2 } // NS records
        val glueARecords: List<com.acktarius.hnsgo.Record> = blockchainRecords.filter { it.type == 1 } // A records (GLUE)
        val glueAAAARecords: List<com.acktarius.hnsgo.Record> = blockchainRecords.filter { it.type == 28 } // AAAA records (GLUE)
        
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
        
        // Add NS records to AUTHORITY section
        nsRecords.forEach { rec ->
            val nameserver = String(rec.data, Charsets.UTF_8).trim()
            val nsName = Name(nameserver, Name.root)
            response.addRecord(NSRecord(queryName, DClass.IN, 3600, nsName), Section.AUTHORITY)
            Log.d("HNSGo", "RecursiveResolver: Added NS record: $nameserver")
        }
        
        // Add GLUE records (A/AAAA) to ADDITIONAL section
        // Match GLUE records to NS records (simplified - assumes first NS gets first GLUE)
        var glueAIndex = 0
        var glueAAAAIndex = 0
        
        nsRecords.forEach { nsRec ->
            val nameserver = String(nsRec.data, Charsets.UTF_8).trim()
            val nsName = Name(nameserver, Name.root)
            
            // Add A record if available
            if (glueAIndex < glueARecords.size) {
                val glueA = glueARecords[glueAIndex]
                val ip = String(glueA.data, Charsets.UTF_8).trim()
                try {
                    response.addRecord(ARecord(nsName, DClass.IN, 3600, InetAddress.getByName(ip)), Section.ADDITIONAL)
                    Log.d("HNSGo", "RecursiveResolver: Added GLUE A: $ip for $nameserver")
                    glueAIndex++
                } catch (e: Exception) {
                    Log.e("HNSGo", "RecursiveResolver: Error parsing GLUE A: $ip", e)
                }
            }
            
            // Add AAAA record if available
            if (glueAAAAIndex < glueAAAARecords.size) {
                val glueAAAA = glueAAAARecords[glueAAAAIndex]
                val ip = String(glueAAAA.data, Charsets.UTF_8).trim()
                try {
                    response.addRecord(AAAARecord(nsName, DClass.IN, 3600, InetAddress.getByName(ip)), Section.ADDITIONAL)
                    Log.d("HNSGo", "RecursiveResolver: Added GLUE AAAA: $ip for $nameserver")
                    glueAAAAIndex++
                } catch (e: Exception) {
                    Log.e("HNSGo", "RecursiveResolver: Error parsing GLUE AAAA: $ip", e)
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
        val nameserverIPs = mutableListOf<Pair<String, Boolean>>() // (IP, isIPv6)
        
        // Add IPv4 addresses
        glueARecords.forEach { rec ->
            val ip = String(rec.data, Charsets.UTF_8).trim()
            nameserverIPs.add(Pair(ip, false))
        }
        
        // Add IPv6 addresses
        glueAAAARecords.forEach { rec ->
            val ip = String(rec.data, Charsets.UTF_8).trim()
            nameserverIPs.add(Pair(ip, true))
        }
        
        if (nameserverIPs.isEmpty()) {
            Log.w("HNSGo", "RecursiveResolver: No nameserver IPs available (no GLUE records)")
            return@withContext null
        }
        
        Log.d("HNSGo", "RecursiveResolver: Querying ${nameserverIPs.size} nameservers for '$name'")
        
        // Try each nameserver
        for ((ip, _) in nameserverIPs) {
            try {
                val answer = queryNameserver(name, type, ip)
                if (answer != null) {
                    val answers = answer.getSection(Section.ANSWER)
                    if (answers.isNotEmpty()) {
                        Log.d("HNSGo", "RecursiveResolver: Got answer from $ip: ${answers.size} records")
                        return@withContext answer
                    }
                }
            } catch (e: Exception) {
                Log.w("HNSGo", "RecursiveResolver: Error querying nameserver $ip: ${e.message}")
                continue
            }
        }
        
        Log.w("HNSGo", "RecursiveResolver: All nameservers failed for '$name'")
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

