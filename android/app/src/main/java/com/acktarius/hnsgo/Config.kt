package com.acktarius.hnsgo

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Application configuration constants
 */
object Config {
    // Dedicated Thread Dispatchers Configuration
    // Strategy: Single-thread for strict ordering (header sync), small pools for high-fan-out operations
    // 
    // Benefits:
    // - Sequential execution per domain simplifies state management and avoids race conditions
    // - Clear thread names make logs and thread dumps easier to interpret
    // - Configurable thread counts allow tuning per environment without code changes
    //
    // Trade-offs:
    // - Single-threaded domains can become bottlenecks under high load
    // - Fixed pools may under-utilize shared Dispatchers.IO/Default pools
    // - Best for correctness-first design; can be optimized later based on profiling
    
    // Thread pool sizes (configurable per environment)
    // Adjust these based on profiling and load characteristics
    const val HEADER_SYNC_THREADS = 1  // Single thread for strict ordering (sequential header sync)
    const val NAME_QUERY_THREADS = 3  // Small pool for concurrent name queries (2-4 threads recommended)
    const val PEER_DISCOVERY_THREADS = 2  // Small pool for peer discovery operations (2-4 threads recommended)
    
    // Thread counters for naming
    private val headerSyncThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val nameQueryThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val peerDiscoveryThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)
    
    /**
     * Header Sync Dispatcher
     * Single-threaded for strict ordering - header sync must be sequential to maintain chain integrity
     * This ensures headers are processed in order and avoids race conditions in chain state
     */
    val HEADER_SYNC_DISPATCHER: ExecutorCoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(HEADER_SYNC_THREADS) { r ->
            Thread(r, "HNS-HeaderSync-${headerSyncThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
    
    /**
     * Name Query Dispatcher
     * Small fixed pool for concurrent DNS name resolution queries to P2P peers
     * Allows multiple queries to run in parallel while maintaining isolation from other subsystems
     */
    val NAME_QUERY_DISPATCHER: ExecutorCoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(NAME_QUERY_THREADS) { r ->
            Thread(r, "HNS-NameQuery-${nameQueryThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
    
    /**
     * Peer Discovery Dispatcher
     * Small fixed pool for peer discovery and connection management operations
     * Allows concurrent peer discovery operations while maintaining isolation
     */
    val PEER_DISCOVERY_DISPATCHER: ExecutorCoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(PEER_DISCOVERY_THREADS) { r ->
            Thread(r, "HNS-PeerDiscovery-${peerDiscoveryThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
    // DEBUG: External resolver for development/testing only (not production)
    // Set these only during development for testing - app should be autonomous in production
    const val DEBUG_RESOLVER_HOST = "127.0.0.1"  // Debug host (localhost only - change during dev if needed)
    const val DEBUG_RESOLVER_PORT = 5341  // Debug resolver port
    
    // DoH/DoT Server Configuration
    const val DOH_PORT = 8443  // HTTPS DoH server port
    const val DOT_PORT = 1853  // DoT server port (above 1024, no root required)
    
    // SPV Configuration (from hnsd constants.h and store.h)
    const val HEADER_SYNC_BATCH_SIZE = 100  // Save headers every N headers
    const val HEADER_SYNC_TIMEOUT_SECONDS = 30
    // Matching hnsd: saves checkpoint every HSK_STORE_CHECKPOINT_WINDOW (2000) blocks (chain.c:750)
    // But we save more frequently for Android (app may close) - every 2000 blocks for checkpoint-style saves
    const val HEADER_SAVE_FREQUENCY_THRESHOLD = 2000  // Save every N new headers (matching HSK_STORE_CHECKPOINT_WINDOW)
    const val HEADER_SAVE_CHAIN_INTERVAL = 2000  // Save every N headers in chain (matching HSK_STORE_CHECKPOINT_WINDOW)
    const val CHECKPOINT_HEIGHT = 136000  // Mainnet checkpoint height (from checkpoints.h)
    const val CHECKPOINT_WINDOW = 2000  // HSK_STORE_CHECKPOINT_WINDOW from constants.h (exact match)
    const val CHECKPOINT_HEADERS_COUNT = 150  // HSK_STORE_HEADERS_COUNT from store.h (exact match)
    const val TREE_INTERVAL = 36  // HSK_TREE_INTERVAL from constants.h
    const val MAX_VALID_HEIGHT = 500000  // Maximum valid blockchain height (sanity check to prevent corrupted data)
    
    // Protocol Constants (from hnsd constants.h)
    const val PROTO_VERSION = 1  // HSK_PROTO_VERSION
    const val SERVICES = 0L  // HSK_SERVICES (no relay or fullnode service)
    const val MAX_MESSAGE_SIZE = 8 * 1000 * 1000  // HSK_MAX_MESSAGE (8MB)
    const val MAX_DATA_SIZE = 668  // HSK_MAX_DATA_SIZE
    const val MAX_VALUE_SIZE = 512  // HSK_MAX_VALUE_SIZE
    
    // Network Constants (from hnsd constants.h - Mainnet)
    const val MAGIC_MAINNET = 0x5b6ef2d3.toInt()  // HSK_MAGIC (mainnet)
    const val P2P_PORT = 12038  // HSK_PORT (mainnet TCP P2P)
    const val BRONTIDE_PORT = 44806  // HSK_BRONTIDE_PORT (encrypted P2P, optional)
    const val NS_PORT = 5349  // HSK_NS_PORT (hnsd Name Server UDP DNS port)
    const val RS_PORT = 5350  // HSK_RS_PORT (hnsd Recursive Server UDP DNS port)
    const val BITS = 0x1c00ffff  // HSK_BITS (difficulty target)
    
    // Target/Retarget Constants (from hnsd constants.h)
    const val TARGET_WINDOW = 144  // HSK_TARGET_WINDOW
    const val TARGET_SPACING = 10 * 60  // HSK_TARGET_SPACING (10 minutes in seconds)
    const val TARGET_TIMESPAN = TARGET_WINDOW * TARGET_SPACING  // HSK_TARGET_TIMESPAN
    const val MIN_ACTUAL = TARGET_TIMESPAN / 4  // HSK_MIN_ACTUAL
    const val MAX_ACTUAL = TARGET_TIMESPAN * 4  // HSK_MAX_ACTUAL
    const val MAX_TIP_AGE = 24 * 60 * 60  // HSK_MAX_TIP_AGE (24 hours in seconds)
    
    // Brontide (Encrypted P2P) - OPTIONAL
    // Brontide uses ChaCha20-Poly1305 for encrypted connections
    // hnsd supports both plain TCP (port 12038) and encrypted brontide (port 44806)
    // We use plain TCP for simplicity - brontide is optional, not required
    const val USE_BRONTIDE = false  // Set to true if we want encrypted connections (future enhancement)
    
    // P2P Connection Configuration
    const val P2P_CONNECT_TIMEOUT_MS = 10000  // Connection timeout (10 seconds)
    const val P2P_SOCKET_TIMEOUT_MS = 30000  // Socket read timeout (30 seconds)
    const val P2P_MAX_RETRIES = 3  // Max retries per seed node
    const val P2P_RETRY_BASE_DELAY_MS = 1000L  // Base delay for exponential backoff (1 second)
    
    // DNS Resolution Configuration
    const val HANDSHAKE_RESOLUTION_TIMEOUT_MS = 15000L  // Timeout for Handshake domain resolution (15 seconds)
    
    // Note: RESERVED_THREADS and MAX_PARALLEL_PEER_CONNECTIONS removed
    // We now use 3 dedicated thread dispatchers (HEADER_SYNC_DISPATCHER, NAME_QUERY_DISPATCHER, PEER_DISCOVERY_DISPATCHER)
    // Each dispatcher has 1 dedicated thread to avoid thread competition
    
    // DHT (Kademlia) Configuration
    const val DHT_PORT = 12038  // UDP port for DHT (same as P2P TCP port)
    const val DHT_K_BUCKET_SIZE = 20  // Number of nodes per Kademlia bucket
    const val DHT_ALPHA = 3  // Concurrency parameter for lookups
    const val DHT_QUERY_TIMEOUT_MS = 5000L  // DHT query timeout (5 seconds)
    const val DHT_PING_TIMEOUT_MS = 3000L  // DHT ping timeout (3 seconds)
    const val DHT_PEER_EXPIRY_SECONDS = 3600L  // Peer expiration time (1 hour)
    
    // Cache Configuration
    const val DNS_CACHE_TTL_SECONDS = 21600  // 6 hours default TTL (matching hnsd authoritative server cache)
    
    // Peer Discovery Configuration
    // DNS Seeds (Primary method for peer discovery)
    // Source: https://github.com/handshake-org/hsd/blob/master/lib/protocol/networks.js
    val DNS_SEEDS = listOf(
        "seed.htools.work"          // Rithvik Vibhu (working DNS seed)
        // Note: seed.easyhandshake.com is mentioned in hnsd seeds.h but is not a DNS seed server
        // It was used to discover hardcoded IPs, which are now in HardcodedPeers.kt
    )
    
    // Hardcoded Peers Configuration
    const val MAX_FALLBACK_PEERS = 10  // Maximum number of verified fallback peers to maintain
    const val MAINNET_PORT = 12038  // Handshake mainnet P2P port
    
    // Development/Debug Configuration
    const val DEBUG_MODE = false  // Set to true only for development (enables HTTP checkpoint fetch)
    
    // File Names
    const val HEADERS_FILE = "headers.dat"
    const val HEADERS_CHECKSUM = "headers.checksum"
    const val HEADERS_METADATA = "headers.meta"
    const val PEERS_FILE = "peers.dat"  // Stored discovered peers for persistence
    
    // DNS Message Constants (from hnsd dns.h - for DNS resolver implementation)
    const val DNS_MAX_NAME = 255  // HSK_DNS_MAX_NAME
    const val DNS_MAX_LABEL = 63  // HSK_DNS_MAX_LABEL
    const val DNS_MAX_UDP = 512  // HSK_DNS_MAX_UDP (standard DNS UDP size)
    const val DNS_MAX_TCP = 65535  // HSK_DNS_MAX_TCP
    const val DNS_STD_EDNS = 1280  // HSK_DNS_STD_EDNS (EDNS0 buffer size)
    const val DNS_MAX_EDNS = 4096  // HSK_DNS_MAX_EDNS
    
    // Handshake Resource Record Types (from hnsd resource.h)
    const val HSK_DS = 0  // DS record
    const val HSK_NS = 1  // NS record
    const val HSK_GLUE4 = 2  // GLUE4 record (IPv4 glue)
    const val HSK_GLUE6 = 3  // GLUE6 record (IPv6 glue)
    const val HSK_SYNTH4 = 4  // SYNTH4 record (synthetic IPv4)
    const val HSK_SYNTH6 = 5  // SYNTH6 record (synthetic IPv6)
    const val HSK_TEXT = 6  // TEXT record
    
    // DNS Record Types (from hnsd dns.h)
    const val HSK_DNS_A = 1  // IPv4 address record
    const val HSK_DNS_AAAA = 28  // IPv6 address record
    const val HSK_DNS_NS = 2  // Nameserver record
    const val HSK_DNS_DS = 43  // DNSSEC delegation signer
    const val HSK_DNS_TXT = 16  // Text record
    const val HSK_DNS_ANY = 255  // Any record type (for queries)
    
    // DNS Response Codes (from hnsd dns.h)
    const val DNS_NOERROR = 0  // No Error
    const val DNS_FORMERR = 1  // Format Error
    const val DNS_SERVFAIL = 2  // Server Failure
    const val DNS_NXDOMAIN = 3  // Non-Existent Domain
    const val DNS_NOTIMP = 4  // Not Implemented
    const val DNS_REFUSED = 5  // Query Refused
    
    // Blacklisted TLDs (from hnsd ns.c:475-503)
    // These TLDs are reserved for other naming systems and should return NXDOMAIN
    val BLACKLISTED_TLDS = setOf(
        "bit",    // Namecoin
        "eth",    // ENS (Ethereum Name Service)
        "exit",   // Tor
        "gnu",    // GNUnet (GNS)
        "i2p",    // Invisible Internet Project
        "onion",  // Tor
        "tor",    // OnioNS
        "zkey"    // GNS
    )
}

