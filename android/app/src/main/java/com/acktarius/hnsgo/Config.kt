package com.acktarius.hnsgo

/**
 * Application configuration constants
 */
object Config {
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
    
    // Parallel Peer Connection Configuration (Rolling Cadence)
    const val RESERVED_THREADS = 4  // Reserve threads for other work (hash computation, I/O, etc.)
    const val MAX_PARALLEL_PEER_CONNECTIONS = 3  // Max parallel peer connections for header fetching pipeline
    
    // DHT (Kademlia) Configuration
    const val DHT_PORT = 12038  // UDP port for DHT (same as P2P TCP port)
    const val DHT_K_BUCKET_SIZE = 20  // Number of nodes per Kademlia bucket
    const val DHT_ALPHA = 3  // Concurrency parameter for lookups
    const val DHT_QUERY_TIMEOUT_MS = 5000L  // DHT query timeout (5 seconds)
    const val DHT_PING_TIMEOUT_MS = 3000L  // DHT ping timeout (3 seconds)
    const val DHT_PEER_EXPIRY_SECONDS = 3600L  // Peer expiration time (1 hour)
    
    // Cache Configuration
    const val DNS_CACHE_TTL_SECONDS = 3600  // 1 hour default TTL
    
    // Peer Discovery Configuration
    // DNS Seeds (Primary method for peer discovery)
    // Source: https://github.com/handshake-org/hsd/blob/master/lib/protocol/networks.js
    val DNS_SEEDS = listOf(
        "hs-mainnet.bcoin.ninja",  // Christopher Jeffrey
        "seed.htools.work"          // Rithvik Vibhu
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
    
    // DNS Response Codes (from hnsd dns.h)
    const val DNS_NOERROR = 0  // No Error
    const val DNS_FORMERR = 1  // Format Error
    const val DNS_SERVFAIL = 2  // Server Failure
    const val DNS_NXDOMAIN = 3  // Non-Existent Domain
    const val DNS_NOTIMP = 4  // Not Implemented
    const val DNS_REFUSED = 5  // Query Refused
}

