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
                0 -> addNSRecord(rec, dnsRecords)
                1 -> addGlue4Record(rec, dnsRecords)
                2 -> addGlue6Record(rec, dnsRecords)
                5 -> addDSRecord(rec, dnsRecords)
                6 -> addTextRecord(rec, dnsRecords)
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
        dnsRecords.add(com.acktarius.hnsgo.Record(2, nameserver.toByteArray(Charsets.UTF_8)))
    }
    
    private fun addGlue4Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        if (rec.data.size != 4) {
            Log.w("HNSGo", "ResourceRecord: Invalid GLUE4 record size: ${rec.data.size} (expected 4)")
            return
        }
        val ip = InetAddress.getByAddress(rec.data)
        val ipStr = ip.hostAddress
        if (ipStr != null) {
            Log.d("HNSGo", "ResourceRecord: Found GLUE4 record: $ipStr")
            dnsRecords.add(com.acktarius.hnsgo.Record(1, ipStr.toByteArray(Charsets.UTF_8)))
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to get host address for GLUE4 record")
        }
    }
    
    private fun addGlue6Record(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        if (rec.data.size != 16) {
            Log.w("HNSGo", "ResourceRecord: Invalid GLUE6 record size: ${rec.data.size} (expected 16)")
            return
        }
        val ip = InetAddress.getByAddress(rec.data)
        val ipStr = ip.hostAddress
        if (ipStr != null) {
            Log.d("HNSGo", "ResourceRecord: Found GLUE6 record: $ipStr")
            dnsRecords.add(com.acktarius.hnsgo.Record(28, ipStr.toByteArray(Charsets.UTF_8)))
        } else {
            Log.w("HNSGo", "ResourceRecord: Failed to get host address for GLUE6 record")
        }
    }
    
    private fun addDSRecord(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        Log.d("HNSGo", "ResourceRecord: Found DS record (${rec.data.size} bytes)")
        dnsRecords.add(com.acktarius.hnsgo.Record(43, rec.data))
    }
    
    private fun addTextRecord(rec: com.acktarius.hnsgo.Record, dnsRecords: MutableList<com.acktarius.hnsgo.Record>) {
        val text = String(rec.data, Charsets.UTF_8).trim()
        Log.d("HNSGo", "ResourceRecord: Found TEXT record: $text")
        dnsRecords.add(com.acktarius.hnsgo.Record(16, text.toByteArray(Charsets.UTF_8)))
    }
}

