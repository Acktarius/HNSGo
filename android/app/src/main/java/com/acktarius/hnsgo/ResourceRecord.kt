package com.acktarius.hnsgo

import android.util.Log
import java.net.InetAddress

/**
 * Handshake Resource Record Utilities
 * 
 * Handles conversion between Handshake blockchain resource records and DNS format.
 * Based on hnsd/src/resource.c:hsk_resource_to_dns
 * 
 * Handshake resource types (from blockchain):
 * - HSK_NS (0): Nameserver record
 * - HSK_GLUE4 (1): IPv4 glue record (4 bytes)
 * - HSK_GLUE6 (2): IPv6 glue record (16 bytes)
 * - HSK_SYNTH4 (3): Synthetic IPv4 (IP encoded as base32 name)
 * - HSK_SYNTH6 (4): Synthetic IPv6
 * - HSK_DS (5): DNSSEC delegation signer
 * - HSK_TEXT (6): Text record
 * 
 * DNS record types (output):
 * - NS (2): Nameserver
 * - A (1): IPv4 address
 * - AAAA (28): IPv6 address
 * - DS (43): DNSSEC delegation signer
 * - TXT (16): Text record
 */
object ResourceRecord {
    /**
     * Extract TLD from domain name and lowercase it (EXACT MATCH to hnsd req.c:96-103)
     * For "website.conceal" -> "conceal"
     * For "conceal" -> "conceal"
     * 
     * hnsd req.c:96: hsk_dns_label_get(qs->name, -1, req->tld);
     * hnsd req.c:103: hsk_to_lower(req->tld);
     * hnsd utils.c:222-232: hsk_to_lower only lowercases A-Z, preserves symbols!
     * 
     * @param name Domain name (may include subdomain)
     * @return Top-level domain (TLD) - extracted and lowercased (letters only, symbols preserved)
     */
    fun extractTLD(name: String): String {
        // Extract last label (TLD) - matching hnsd req.c:96
        val trimmed = name.trimEnd('.')
        val parts = trimmed.split('.')
        val tld = if (parts.size > 1) {
            parts.last() // Return last part (TLD)
        } else {
            trimmed // Already a TLD
        }
        
        // Lowercase ONLY letters (A-Z -> a-z), preserve symbols - matching hnsd utils.c:222-232
        return tld.map { char ->
            if (char in 'A'..'Z') {
                char + 32 // Lowercase: 'A' (65) -> 'a' (97), difference is 32
            } else {
                char // Preserve symbols, numbers, and already lowercase letters
            }
        }.joinToString("")
    }
    
    /**
     * Convert Handshake resource records to DNS format
     * Based on hnsd/src/resource.c:hsk_resource_to_dns
     * 
     * Handshake blockchain stores resource records for TLDs only.
     * This function converts those records to standard DNS format:
     * - NS records in authority section
     * - GLUE4/GLUE6 converted to A/AAAA records in additional section
     * 
     * @param blockchainRecords List of Handshake resource records from blockchain
     * @return List of DNS records in standard format
     */
    fun convertHandshakeRecordsToDNS(
        blockchainRecords: List<com.acktarius.hnsgo.Record>
    ): List<com.acktarius.hnsgo.Record> {
        val dnsRecords = mutableListOf<com.acktarius.hnsgo.Record>()
        
        for (rec in blockchainRecords) {
            when (rec.type) {
                Config.HSK_DS -> addDSRecord(rec, dnsRecords)
                Config.HSK_NS -> addNSRecord(rec, dnsRecords)
                Config.HSK_GLUE4 -> addGlue4Record(rec, dnsRecords)
                Config.HSK_GLUE6 -> addGlue6Record(rec, dnsRecords)
                Config.HSK_SYNTH4 -> addSynth4Record(rec, dnsRecords)
                Config.HSK_SYNTH6 -> addSynth6Record(rec, dnsRecords)
                Config.HSK_TEXT -> addTextRecord(rec, dnsRecords)
                else -> {
                    Log.d("HNSGo", "ResourceRecord: Unknown resource record type: ${rec.type}")
                }
            }
        }
        
        Log.d("HNSGo", "ResourceRecord: Converted ${blockchainRecords.size} Handshake records to ${dnsRecords.size} DNS records")
        return dnsRecords
    }
    
    private fun addNSRecord(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        val nameserver = String(rec.data, Charsets.UTF_8).trim()
        Log.d("HNSGo", "ResourceRecord: Found NS record: $nameserver")
        dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_NS, nameserver.toByteArray(Charsets.UTF_8)))
    }
    
    private fun addGlue4Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        // GLUE4 record format: name (UTF-8) + null byte + IP (4 bytes)
        // Find null terminator
        var nullIndex = -1
        for (i in rec.data.indices) {
            if (rec.data[i].toInt() == 0) {
                nullIndex = i
                break
            }
        }
        
        if (nullIndex < 0 || nullIndex + 5 > rec.data.size) {
            // Fallback: try old format (just 4 bytes IP)
            if (rec.data.size == 4) {
                val ip = InetAddress.getByAddress(rec.data)
                val ipStr = ip.hostAddress
                if (ipStr != null) {
                    Log.d("HNSGo", "ResourceRecord: Found GLUE4 record (old format): $ipStr")
                    dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_A, ipStr.toByteArray(Charsets.UTF_8)))
                }
                return
            }
            Log.w("HNSGo", "ResourceRecord: Invalid GLUE4 record format: ${rec.data.size} bytes")
            return
        }
        
        val name = String(rec.data, 0, nullIndex, Charsets.UTF_8)
        val ipBytes = rec.data.sliceArray(nullIndex + 1 until nullIndex + 5)
        val ip = InetAddress.getByAddress(ipBytes)
        val ipStr = ip.hostAddress
        if (ipStr != null) {
            Log.d("HNSGo", "ResourceRecord: Found GLUE4 record: $name -> $ipStr")
            // Store as: name + null + IP string (for matching to NS records)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val ipStrBytes = ipStr.toByteArray(Charsets.UTF_8)
            val recordData = ByteArray(nameBytes.size + 1 + ipStrBytes.size)
            System.arraycopy(nameBytes, 0, recordData, 0, nameBytes.size)
            recordData[nameBytes.size] = 0 // Null byte
            System.arraycopy(ipStrBytes, 0, recordData, nameBytes.size + 1, ipStrBytes.size)
            dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_A, recordData))
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to get host address for GLUE4 record: $name")
        }
    }
    
    private fun addGlue6Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        // GLUE6 record format: name (UTF-8) + null byte + IP (16 bytes)
        // Find null terminator
        var nullIndex = -1
        for (i in rec.data.indices) {
            if (rec.data[i].toInt() == 0) {
                nullIndex = i
                break
            }
        }
        
        if (nullIndex < 0 || nullIndex + 17 > rec.data.size) {
            // Fallback: try old format (just 16 bytes IP)
            if (rec.data.size == 16) {
                val ip = InetAddress.getByAddress(rec.data)
                val ipStr = ip.hostAddress
                if (ipStr != null) {
                    Log.d("HNSGo", "ResourceRecord: Found GLUE6 record (old format): $ipStr")
                    dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_AAAA, ipStr.toByteArray(Charsets.UTF_8)))
                }
                return
            }
            Log.w("HNSGo", "ResourceRecord: Invalid GLUE6 record format: ${rec.data.size} bytes")
            return
        }
        
        val name = String(rec.data, 0, nullIndex, Charsets.UTF_8)
        val ipBytes = rec.data.sliceArray(nullIndex + 1 until nullIndex + 17)
        val ip = InetAddress.getByAddress(ipBytes)
        val ipStr = ip.hostAddress
        if (ipStr != null) {
            Log.d("HNSGo", "ResourceRecord: Found GLUE6 record: $name -> $ipStr")
            // Store as: name + null + IP string (for matching to NS records)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val ipStrBytes = ipStr.toByteArray(Charsets.UTF_8)
            val recordData = ByteArray(nameBytes.size + 1 + ipStrBytes.size)
            System.arraycopy(nameBytes, 0, recordData, 0, nameBytes.size)
            recordData[nameBytes.size] = 0 // Null byte
            System.arraycopy(ipStrBytes, 0, recordData, nameBytes.size + 1, ipStrBytes.size)
            dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_AAAA, recordData))
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to get host address for GLUE6 record: $name")
        }
    }
    
    private fun addDSRecord(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        Log.d("HNSGo", "ResourceRecord: Found DS record (${rec.data.size} bytes)")
        dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_DS, rec.data))
    }
    
    private fun addSynth4Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        // SYNTH4: DNS name contains base32-encoded IPv4 address
        // For now, we'll decode the name to get the IP
        // Matching hnsd's pointer_to_ip function behavior
        val name = String(rec.data, Charsets.UTF_8).trim()
        val ipBytes = decodeSynthNameToIP(name, 4)
        if (ipBytes != null && ipBytes.size == 4) {
            val ip = InetAddress.getByAddress(ipBytes)
            val ipStr = ip.hostAddress
            if (ipStr != null) {
                Log.d("HNSGo", "ResourceRecord: Found SYNTH4 record: $name -> $ipStr")
                dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_A, ipStr.toByteArray(Charsets.UTF_8)))
            } else {
                Log.w("HNSGo", "ResourceRecord: Failed to get host address for SYNTH4 record")
            }
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to decode SYNTH4 IP from name: $name")
        }
    }
    
    private fun addSynth6Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        // SYNTH6: DNS name contains base32-encoded IPv6 address
        // For now, we'll decode the name to get the IP
        // Matching hnsd's pointer_to_ip function behavior
        val name = String(rec.data, Charsets.UTF_8).trim()
        val ipBytes = decodeSynthNameToIP(name, 16)
        if (ipBytes != null && ipBytes.size == 16) {
            val ip = InetAddress.getByAddress(ipBytes)
            val ipStr = ip.hostAddress
            if (ipStr != null) {
                Log.d("HNSGo", "ResourceRecord: Found SYNTH6 record: $name -> $ipStr")
                dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_AAAA, ipStr.toByteArray(Charsets.UTF_8)))
            } else {
                Log.w("HNSGo", "ResourceRecord: Failed to get host address for SYNTH6 record")
            }
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to decode SYNTH6 IP from name: $name")
        }
    }
    
    /**
     * Decode synthetic name to IP address
     * Matching hnsd's pointer_to_ip function
     * SYNTH names encode IP addresses in base32 format
     * 
     * @param name DNS name containing base32-encoded IP
     * @param expectedBytes Expected IP size (4 for IPv4, 16 for IPv6)
     * @return IP address bytes or null if decoding fails
     */
    @Suppress("UNUSED_PARAMETER")
    private fun decodeSynthNameToIP(name: String, expectedBytes: Int): ByteArray? {
        // TODO: Implement base32 decoding of SYNTH names
        // expectedBytes will be used when implementing the actual decoding
        // For now, return null to indicate we need proper base32 decoding
        // This matches hnsd's pointer_to_ip which decodes base32-encoded names
        // The format is typically: base32(IP) + checksum or similar
        Log.w("HNSGo", "ResourceRecord: SYNTH name decoding not yet implemented: $name")
        return null
    }
    
    private fun addTextRecord(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        val text = String(rec.data, Charsets.UTF_8).trim()
        Log.d("HNSGo", "ResourceRecord: Found TEXT record: $text")
        dnsRecords.add(com.acktarius.hnsgo.Record(Config.HSK_DNS_TXT, text.toByteArray(Charsets.UTF_8)))
    }
}

