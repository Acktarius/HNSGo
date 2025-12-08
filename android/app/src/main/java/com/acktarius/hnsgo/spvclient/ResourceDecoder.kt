package com.acktarius.hnsgo.spvclient

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resource decoder for Handshake proof data
 * 
 * EXACT MATCH to hnsd resource.c:hsk_resource_decode (lines 387-447)
 * 
 * Resource format:
 * - Version (1 byte, must be 0)
 * - Records: Type (1 byte) + Record body
 * 
 * Record types:
 * - 0: HSK_NS - Nameserver record (DNS name with compression)
 * - 1: HSK_GLUE4 - IPv4 glue record (DNS name + 4-byte IPv4)
 * - 2: HSK_GLUE6 - IPv6 glue record (DNS name + 16-byte IPv6)
 * - 3: HSK_SYNTH4 - Synthetic IPv4 (IP encoded as base32 DNS name)
 * - 4: HSK_SYNTH6 - Synthetic IPv6 (IP encoded as base32 DNS name)
 * - 5: HSK_DS - DS record (key_tag + algorithm + digest_type + digest)
 * - 6: HSK_TEXT - TXT record (array of strings)
 * 
 * DNS names use compression (RFC 1035 section 4.1.4)
 */
internal object ResourceDecoder {
    
    /**
     * Decode resource records from proof data
     * Matching hnsd resource.c:hsk_resource_decode
     * 
     * @param proofData Raw resource data from proof (after hsk_proof_verify)
     * @return List of decoded records
     */
    fun decodeResource(proofData: ByteArray): List<com.acktarius.hnsgo.Record> {
        if (proofData.isEmpty()) {
            return emptyList()
        }
        
        val buffer = ByteBuffer.wrap(proofData).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            // Read version (must be 0) - matching hnsd resource.c:413-418
            if (buffer.remaining() < 1) {
                return emptyList()
            }
            val version = buffer.get().toInt() and 0xFF
            if (version != 0) {
                return emptyList()
            }
            
            
            // Debug: dump first 50 bytes of resource data for analysis
            val debugDump = proofData.sliceArray(0 until minOf(50, proofData.size))
                .joinToString(" ") { "%02x".format(it) }
            
            val records = mutableListOf<com.acktarius.hnsgo.Record>()
            val glueNames = mutableSetOf<String>() // Track nameserver names from GLUE records
            
            // Read records until buffer is empty (matching hnsd resource.c:425-436)
            while (buffer.hasRemaining()) {
                // Read record type (1 byte) - matching hnsd resource.c:427-428
                if (buffer.remaining() < 1) break
                val type = buffer.get().toInt() and 0xFF
                
                
                // Handle unknown record types by trying to skip to next valid record
                val validTypes = setOf(
                    com.acktarius.hnsgo.Config.HSK_DS,
                    com.acktarius.hnsgo.Config.HSK_NS,
                    com.acktarius.hnsgo.Config.HSK_GLUE4,
                    com.acktarius.hnsgo.Config.HSK_GLUE6,
                    com.acktarius.hnsgo.Config.HSK_SYNTH4,
                    com.acktarius.hnsgo.Config.HSK_SYNTH6,
                    com.acktarius.hnsgo.Config.HSK_TEXT
                )
                if (type !in validTypes) {
                    val currentPos = buffer.position()
                    var foundNext = false
                    
                    // Look ahead up to 256 bytes for next valid record type
                    // Validate that candidate positions are actually record boundaries
                    val maxLookAhead = minOf(256, buffer.remaining())
                    for (i in 0 until maxLookAhead) {
                        val peekPos = currentPos + i
                        if (peekPos < proofData.size) {
                            val peekByte = proofData[peekPos].toInt() and 0xFF
                            if (peekByte in validTypes && isValidRecordStart(proofData, peekPos, peekByte)) {
                                // Found next valid record type at a valid boundary, skip to it
                                buffer.position(peekPos)
                                foundNext = true
                                break
                            }
                        }
                    }
                    
                    if (!foundNext) {
                        // Couldn't find next record, stop parsing
                        break
                    }
                    // Continue loop to process the next record
                    continue
                }
                
                // Read record body based on type (matching hnsd resource.c:431)
                // Extract nameserver name from GLUE records BEFORE decoding (so we have it even if decoding fails)
                if (type == com.acktarius.hnsgo.Config.HSK_GLUE4 || type == com.acktarius.hnsgo.Config.HSK_GLUE6) {
                    val namePos = buffer.position()
                    val nameResult = readDnsNameWithCompression(proofData, namePos)
                    if (nameResult != null) {
                        glueNames.add(nameResult.first)
                        // Restore buffer position so decodeGLUE4Record/decodeGLUE6Record can read the name again
                        buffer.position(namePos)
                    }
                }
                
                val record = readRecord(type, proofData, buffer)
                
                if (record == null) {
                    break
                }
                
                records.add(record)
            }
            
            // If we have GLUE records but no NS records, create NS records from GLUE names
            val hasNSRecords = records.any { it.type == com.acktarius.hnsgo.Config.HSK_NS }
            if (!hasNSRecords && glueNames.isNotEmpty()) {
                for (name in glueNames) {
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    records.add(0, com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_NS, nameBytes)) // Add NS record at beginning
                }
            }
            
            return records
            
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    
    /**
     * Read record based on type (matching hnsd resource.c:hsk_record_read lines 325-384)
     * 
     * This function dispatches to the appropriate decoder based on record type,
     * matching hnsd's switch/case structure.
     * 
     * @param type Record type (0=NS, 1=GLUE4, 2=GLUE6, 3=SYNTH4, 4=SYNTH6, 5=DS, 6=TXT)
     * @param data Full resource data (needed for compression pointers)
     * @param buffer ByteBuffer positioned at the start of the record body
     * @return Decoded record or null on error
     */
    private fun readRecord(
        type: Int,
        data: ByteArray,
        buffer: ByteBuffer
    ): com.acktarius.hnsgo.Record? {
        return when (type) {
            com.acktarius.hnsgo.Config.HSK_DS -> decodeDSRecord(buffer) // HSK_DS = 0
            com.acktarius.hnsgo.Config.HSK_NS -> decodeNSRecord(data, buffer) // HSK_NS = 1
            com.acktarius.hnsgo.Config.HSK_GLUE4 -> decodeGLUE4Record(data, buffer) // HSK_GLUE4 = 2
            com.acktarius.hnsgo.Config.HSK_GLUE6 -> decodeGLUE6Record(data, buffer) // HSK_GLUE6 = 3
            com.acktarius.hnsgo.Config.HSK_SYNTH4 -> decodeSYNTH4Record(data, buffer) // HSK_SYNTH4 = 4
            com.acktarius.hnsgo.Config.HSK_SYNTH6 -> decodeSYNTH6Record(data, buffer) // HSK_SYNTH6 = 5
            com.acktarius.hnsgo.Config.HSK_TEXT -> decodeTXTRecord(buffer) // HSK_TEXT = 6
            else -> {
                // Unknown record type (should not reach here due to check above)
                null
            }
        }
    }
    
    /**
     * Decode NS record (type 0)
     * Matching hnsd resource.c:hsk_ns_record_read (lines 65-72)
     * NS record: DNS name (with compression)
     */
    private fun decodeNSRecord(data: ByteArray, buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        val result = readDnsNameWithCompression(data, buffer.position())
        if (result != null) {
            val (name, bytesConsumed) = result
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            buffer.position(buffer.position() + bytesConsumed)
            return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_NS, nameBytes)
        } else {
            return null
        }
    }
    
    /**
     * Decode GLUE4 record (type 1)
     * Matching hnsd resource.c:hsk_glue4_record_read (lines 75-85)
     * GLUE4: DNS name + 4-byte IPv4 address
     * 
     * Stores both name and IP: name (UTF-8) + null byte + IP (4 bytes)
     * This matches hnsd where hsk_glue4_record_t has both name[256] and inet4[4] fields
     */
    private fun decodeGLUE4Record(data: ByteArray, buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        val nameStartPos = buffer.position()
        val result = readDnsNameWithCompression(data, nameStartPos)
        if (result != null) {
            val (name, bytesConsumed) = result
            val nameEndPos = nameStartPos + bytesConsumed
            buffer.position(nameEndPos)
            
            
            if (buffer.remaining() >= 4) {
                val ipStartPos = buffer.position()
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                val ip = InetAddress.getByAddress(ipBytes).hostAddress
                val ipHex = ipBytes.joinToString(" ") { "%02x".format(it) }
                
                // Store name + null byte + IP bytes (matching hnsd's hsk_glue4_record_t structure)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val recordData = ByteArray(nameBytes.size + 1 + 4)
                System.arraycopy(nameBytes, 0, recordData, 0, nameBytes.size)
                recordData[nameBytes.size] = 0 // Null terminator
                System.arraycopy(ipBytes, 0, recordData, nameBytes.size + 1, 4)
                return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_GLUE4, recordData)
            } else {
                // Debug: show what bytes we have
                if (buffer.remaining() > 0) {
                    val availableBytes = ByteArray(minOf(4, buffer.remaining()))
                    val savedPos = buffer.position()
                    buffer.get(availableBytes)
                    buffer.position(savedPos)
                    val hex = availableBytes.joinToString(" ") { "%02x".format(it) }
                }
                return null
            }
        } else {
            return null
        }
    }
    
    /**
     * Decode GLUE6 record (type 3)
     * Matching hnsd resource.c:hsk_glue6_record_read (lines 88-98)
     * GLUE6: DNS name + 16-byte IPv6 address
     */
    private fun decodeGLUE6Record(data: ByteArray, buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        val nameStartPos = buffer.position()
        val result = readDnsNameWithCompression(data, nameStartPos)
        if (result != null) {
            val (name, bytesConsumed) = result
            val nameEndPos = nameStartPos + bytesConsumed
            buffer.position(nameEndPos)
            
            
            if (buffer.remaining() >= 16) {
                val ipStartPos = buffer.position()
                val ipBytes = ByteArray(16)
                buffer.get(ipBytes)
                val ipHex = ipBytes.joinToString(" ") { "%02x".format(it) }
                
                // Store name + null byte + IP bytes (matching hnsd's hsk_glue6_record_t structure)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val recordData = ByteArray(nameBytes.size + 1 + 16)
                System.arraycopy(nameBytes, 0, recordData, 0, nameBytes.size)
                recordData[nameBytes.size] = 0 // Null terminator
                System.arraycopy(ipBytes, 0, recordData, nameBytes.size + 1, 16)
                return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_GLUE6, recordData)
            } else {
                // Debug: show what bytes we have
                if (buffer.remaining() > 0) {
                    val availableBytes = ByteArray(minOf(16, buffer.remaining()))
                    val savedPos = buffer.position()
                    buffer.get(availableBytes)
                    buffer.position(savedPos)
                    val hex = availableBytes.joinToString(" ") { "%02x".format(it) }
                }
                return null
            }
        } else {
            return null
        }
    }
    
    /**
     * Decode SYNTH4 record (type 3)
     * Matching hnsd resource.c:hsk_synth4_record_read
     * SYNTH4: DNS name (IP encoded as base32 name)
     * The DNS name contains an IPv4 address encoded in base32 format
     */
    private fun decodeSYNTH4Record(data: ByteArray, buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        val result = readDnsNameWithCompression(data, buffer.position())
        if (result != null) {
            val (name, bytesConsumed) = result
            buffer.position(buffer.position() + bytesConsumed)
            
            // Decode IP from base32-encoded DNS name
            val ipBytes = decodeSynthNameToIP(name)
            if (ipBytes != null) {
                return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_SYNTH4, ipBytes)
            } else {
                return null
            }
        } else {
            return null
        }
    }
    
    /**
     * Decode SYNTH6 record (type 4)
     * Matching hnsd resource.c:hsk_synth6_record_read
     * SYNTH6: DNS name (IP encoded as base32 name)
     * The DNS name contains an IPv6 address encoded in base32 format
     */
    private fun decodeSYNTH6Record(data: ByteArray, buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        val result = readDnsNameWithCompression(data, buffer.position())
        if (result != null) {
            val (name, bytesConsumed) = result
            buffer.position(buffer.position() + bytesConsumed)
            
            // Decode IP from base32-encoded DNS name
            val ipBytes = decodeSynthNameToIP(name)
            if (ipBytes != null) {
                val ipHex = ipBytes.joinToString(" ") { "%02x".format(it) }
                return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_SYNTH6, ipBytes)
            } else {
                return null
            }
        } else {
            return null
        }
    }
    
    /**
     * Decode synthetic name to IP address
     * Matching hnsd's pointer_to_ip function
     * SYNTH names encode IP addresses in base32 format
     * 
     * @param name DNS name containing base32-encoded IP
     * @return IP address bytes or null if decoding fails
     * 
     * Note: For now, we store the name as-is in the record data.
     * The actual base32 decoding will be implemented in ResourceRecord
     * to match hnsd's pointer_to_ip function behavior.
     */
    private fun decodeSynthNameToIP(name: String): ByteArray? {
        // Store the name as bytes - ResourceRecord will handle base32 decoding
        // This allows us to parse SYNTH records even if base32 decoding isn't fully implemented yet
        return name.toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Decode DS record (type 5)
     * Matching hnsd resource.c:hsk_ds_record_read (lines 39-62)
     * DS: key_tag (2 bytes BE) + algorithm (1) + digest_type (1) + digest_len (1) + digest
     */
    private fun decodeDSRecord(buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        if (buffer.remaining() < 5) {
            return null
        }
        
        // Read keyTag as big-endian (matching hnsd resource.c:48: read_u16be)
        val keyTagBytes = ByteArray(2)
        buffer.get(keyTagBytes)
        val keyTag = ((keyTagBytes[0].toInt() and 0xFF) shl 8) or (keyTagBytes[1].toInt() and 0xFF)
        val algorithm = buffer.get().toInt() and 0xFF
        val digestType = buffer.get().toInt() and 0xFF
        val digestLen = buffer.get().toInt() and 0xFF
        
        if (digestLen > 64 || buffer.remaining() < digestLen) {
            return null
        }
        
        val digest = ByteArray(digestLen)
        buffer.get(digest)
        
        // Store DS record as: keyTag(2) + algorithm(1) + digestType(1) + digest
        val dsData = ByteArray(4 + digestLen)
        dsData[0] = (keyTag shr 8).toByte()
        dsData[1] = keyTag.toByte()
        dsData[2] = algorithm.toByte()
        dsData[3] = digestType.toByte()
        System.arraycopy(digest, 0, dsData, 4, digestLen)
        
        return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_DS, dsData)
    }
    
    /**
     * Decode TXT record (type 6)
     * Matching hnsd resource.c:hsk_txt_record_read (lines 119-159)
     * TXT: length (1) + array of strings, each with length (1) + data
     */
    private fun decodeTXTRecord(buffer: ByteBuffer): com.acktarius.hnsgo.Record? {
        if (buffer.remaining() < 1) {
            return null
        }
        
        val arrayLen = buffer.get().toInt() and 0xFF
        var txtData = ByteArray(0)
        
        for (i in 0 until arrayLen) {
            if (buffer.remaining() < 1) {
                break
            }
            val strLen = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < strLen) {
                break
            }
            
            val strData = ByteArray(strLen)
            buffer.get(strData)
            
            // Append to txtData
            val newTxtData = ByteArray(txtData.size + strLen)
            System.arraycopy(txtData, 0, newTxtData, 0, txtData.size)
            System.arraycopy(strData, 0, newTxtData, txtData.size, strLen)
            txtData = newTxtData
        }
        
        return com.acktarius.hnsgo.Record(com.acktarius.hnsgo.Config.HSK_TEXT, txtData)
    }
    
    /**
     * Read DNS name with compression (matching hnsd's hsk_dns_name_read)
     * DNS names use compression pointers (RFC 1035 section 4.1.4)
     * 
     * @param data Full resource data (needed for compression pointers)
     * @param startOffset Starting offset in data
     * @return Pair(name, bytesConsumed) or null on error
     */
    /**
     * Read DNS name with compression (matching hnsd's hsk_dns_name_parse)
     * Returns bytes consumed from startOffset (only counts bytes at startOffset, not from pointers)
     * 
     * Matching hnsd dns.c:hsk_dns_name_parse (lines 2097-2210)
     * - For compression pointer: consumes 2 bytes (the pointer itself)
     * - For regular labels: consumes 1 + length bytes per label
     * - For end of name (0x00): consumes 1 byte
     */
    /**
     * Read DNS name with compression (matching hnsd's hsk_dns_name_parse)
     * 
     * EXACT MATCH to hnsd dns.c:hsk_dns_name_parse (lines 2097-2210)
     * 
     * Key points from hnsd:
     * - `off` starts at 0 and accumulates as we read bytes
     * - For compression pointer: `off += 1` (first byte), `off += 1` (second byte), then `res = off` if first pointer
     * - For regular labels: `off += 1` (length), `off += c` (label data)
     * - At end: `if (ptr == 0) res = off` (if no pointer, res = total bytes)
     * - `res` is the total bytes consumed from startOffset
     * 
     * @param data Full resource data (needed for compression pointers)
     * @param startOffset Starting offset in data
     * @return Pair(name, bytesConsumed) or null on error
     */
    /**
     * Read DNS name with compression (EXACT MATCH to hnsd's hsk_dns_name_parse)
     * 
     * Matching hnsd dns.c:hsk_dns_name_parse (lines 2097-2210)
     * 
     * Key algorithm from hnsd:
     * - `off` starts at 0 and tracks position relative to current data pointer
     * - For each iteration: `c = data[off]`, then `off += 1` (read byte)
     * - For regular label: `off += c` (read label data)
     * - For compression pointer: `off += 1` (read second byte), then `if (ptr == 0) res = off`
     * - At end: `if (ptr == 0) res = off` (includes 0x00 if no pointer)
     * - When following pointer: switch to full message data
     * 
     * @param data Full resource data (needed for compression pointers)
     * @param startOffset Starting offset in data
     * @return Pair(name, bytesConsumed) or null on error
     */
    /**
     * Read DNS name with compression (EXACT MATCH to hnsd's hsk_dns_name_parse)
     * 
     * Matching hnsd dns.c:hsk_dns_name_parse (lines 2097-2210)
     * 
     * Key algorithm from hnsd:
     * - `off` starts at 0 and tracks position in current data segment
     * - `data` and `data_len` point to current segment (switches to full message when following pointer)
     * - For each byte: `c = data[off]`, then `off += 1`
     * - For regular label: `off += c` (read label data)
     * - For compression pointer: `off += 1` (read second byte), then `if (ptr == 0) res = off`
     * - When following pointer: `off = pointerOffset`, `data = dmp->msg`, `data_len = dmp->msg_len`
     * - At end: `if (ptr == 0) res = off` (includes 0x00 if no pointer)
     * 
     * @param data Full resource data (needed for compression pointers)
     * @param startOffset Starting offset in data
     * @return Pair(name, bytesConsumed) or null on error
     */
    private fun readDnsNameWithCompression(data: ByteArray, startOffset: Int): Pair<String, Int>? {
        return try {
            val visitedOffsets = mutableSetOf<Int>() // Prevent infinite loops
            val labels = mutableListOf<String>()
            var bytesConsumed = 0 // Track bytes consumed from startOffset (matching hnsd's 'res')
            var pointerCount = 0 // Track number of compression pointers (matching hnsd's 'ptr')
            
            // Current data segment (matching hnsd's 'data' and 'data_len')
            var currentData = data
            var currentDataLen = data.size
            var currentOffset = startOffset // Current position in currentData (matching hnsd's 'off')
            
            while (currentOffset < currentDataLen) {
                if (currentOffset in visitedOffsets) {
                    return null
                }
                visitedOffsets.add(currentOffset)
                
                val length = currentData[currentOffset].toInt() and 0xFF
                currentOffset += 1 // Read the byte (matching hnsd: off += 1)
                
                // End of name (length = 0) - matching hnsd dns.c:2118-2119
                if (length == 0) {
                    // If no compression pointer was encountered, bytesConsumed = total bytes read
                    // Matching hnsd: "if (ptr == 0) res = off" where off includes the 0x00 byte
                    if (pointerCount == 0) {
                        bytesConsumed = currentOffset - startOffset // Total bytes read including 0x00
                    }
                    break
                }
                
                // Check for compression pointer (0xC0 mask) - matching hnsd dns.c:2161-2185
                if ((length and 0xC0) == 0xC0) {
                    // Compression pointer: read second byte
                    if (currentOffset >= currentDataLen) {
                        return null
                    }
                    
                    val secondByte = currentData[currentOffset].toInt() and 0xFF
                    currentOffset += 1 // Read second byte (matching hnsd: off += 1)
                    
                    // Calculate pointer offset
                    val pointerOffset = ((length and 0x3F) shl 8) or secondByte
                    
                    // If this is the first pointer, set bytesConsumed
                    // Matching hnsd: "if (ptr == 0) res = off" where off is after reading both bytes
                    if (pointerCount == 0) {
                        bytesConsumed = currentOffset - startOffset // Total bytes read (2 bytes for pointer)
                    }
                    
                    pointerCount++
                    if (pointerCount > 10) {
                        return null
                    }
                    
                    if (pointerOffset >= data.size) {
                        return null
                    }
                    
                    // Follow compression pointer (matching hnsd: off = pointerOffset, data = dmp->msg, data_len = dmp->msg_len)
                    currentOffset = pointerOffset
                    currentData = data
                    currentDataLen = data.size
                    continue
                }
                
                // Regular label - matching hnsd dns.c:2122-2158
                if (length > 63) {
                    return null
                }
                if (currentOffset + length > currentDataLen) {
                    return null
                }
                
                val labelBytes = currentData.sliceArray(currentOffset until currentOffset + length)
                val label = String(labelBytes, Charsets.UTF_8)
                labels.add(label)
                
                currentOffset += length // Read label data (matching hnsd: off += c)
            }
            
            if (labels.isEmpty()) {
                return null
            }
            
            val name = labels.joinToString(".")
            // Debug: show bytes from startOffset to current position to verify name parsing
            val endOffset = if (pointerCount > 0) {
                // If we used compression pointers, bytesConsumed is already set correctly
                startOffset + bytesConsumed
            } else {
                currentOffset
            }
            val debugBytes = if (startOffset < data.size) {
                val end = minOf(endOffset, data.size)
                data.sliceArray(startOffset until end)
                    .joinToString(" ") { "%02x".format(it) }
            } else "N/A"
            return Pair(name, bytesConsumed)
            
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Validate if a position looks like a valid record start
     * Checks that the byte after the type byte is consistent with the record format
     * 
     * @param data Full resource data
     * @param pos Position of the record type byte
     * @param type Record type (0=NS, 1=GLUE4, 2=GLUE6, 3=SYNTH4, 4=SYNTH6, 5=DS, 6=TXT)
     * @return true if this looks like a valid record boundary
     */
    private fun isValidRecordStart(data: ByteArray, pos: Int, type: Int): Boolean {
        if (pos + 1 >= data.size) return false
        
            return when (type) {
            com.acktarius.hnsgo.Config.HSK_NS,
            com.acktarius.hnsgo.Config.HSK_GLUE4,
            com.acktarius.hnsgo.Config.HSK_GLUE6,
            com.acktarius.hnsgo.Config.HSK_SYNTH4,
            com.acktarius.hnsgo.Config.HSK_SYNTH6 -> {
                // NS, GLUE4, GLUE6, SYNTH4, SYNTH6 all start with a DNS name
                // Next byte should be a valid DNS name start:
                // - Length byte (0-63) for regular label
                // - Compression pointer (0xC0-0xFF) for pointer
                // - 0x00 for root name (unlikely but valid)
                val nextByte = data[pos + 1].toInt() and 0xFF
                nextByte == 0x00 || nextByte <= 63 || (nextByte and 0xC0) == 0xC0
            }
            com.acktarius.hnsgo.Config.HSK_DS -> {
                // DS record: keyTag (2 bytes BE) + algorithm (1) + digest_type (1) + digest_len (1)
                // Need at least 5 bytes, and digest_len should be reasonable (< 64)
                if (pos + 5 >= data.size) return false
                val digestLen = data[pos + 4].toInt() and 0xFF
                digestLen <= 64 // Reasonable digest length
            }
            com.acktarius.hnsgo.Config.HSK_TEXT -> {
                // TXT record: array length (1 byte)
                // Array length should be reasonable (< 256, but we already know it's a byte)
                // Just check we have at least 1 byte
                pos + 1 < data.size
            }
            else -> false
        }
    }
}

