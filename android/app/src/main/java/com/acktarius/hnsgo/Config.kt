package com.acktarius.hnsgo

/**
 * Application configuration constants
 */
object Config {
    // DNS Resolver Configuration
    // DEBUG ONLY: Using external Handshake resolver for testing
    // In production, SpvClient should resolve domains directly from blockchain data (treeRoot/witnessRoot)
    // TODO: Remove external resolver dependency and implement true SPV domain resolution
    const val DEFAULT_RESOLVER_HOST = "192.168.1.8"  // DEBUG: External Handshake resolver (hnsd)
    const val DEFAULT_RESOLVER_PORT = 5341  // DEBUG: External resolver port
    const val FALLBACK_RESOLVER_HOST = "127.0.0.1"
    const val FALLBACK_RESOLVER_PORT = 5353  // High port (no root required)
    
    // DoH/DoT Server Configuration
    const val DOH_PORT = 8443  // HTTPS DoH server port
    const val DOT_PORT = 1853  // DoT server port (above 1024, no root required)
    
    // SPV Configuration (from hnsd constants.h)
    const val HEADER_SYNC_BATCH_SIZE = 100  // Save headers every N headers
    const val HEADER_SYNC_TIMEOUT_SECONDS = 30
    const val CHECKPOINT_HEIGHT = 136000  // Mainnet checkpoint height (from checkpoints.h)
    const val CHECKPOINT_WINDOW = 2000  // HSK_STORE_CHECKPOINT_WINDOW from constants.h
    const val TREE_INTERVAL = 36  // HSK_TREE_INTERVAL from constants.h
    
    // P2P Connection Configuration
    const val P2P_CONNECT_TIMEOUT_MS = 10000  // Connection timeout (10 seconds)
    const val P2P_SOCKET_TIMEOUT_MS = 30000  // Socket read timeout (30 seconds)
    const val P2P_MAX_RETRIES = 3  // Max retries per seed node
    const val P2P_RETRY_BASE_DELAY_MS = 1000L  // Base delay for exponential backoff (1 second)
    
    // Cache Configuration
    const val DNS_CACHE_TTL_SECONDS = 3600  // 1 hour default TTL
    
    // Development/Debug Configuration
    const val DEBUG_MODE = false  // Set to true only for development (enables HTTP checkpoint fetch)
    
    // File Names
    const val HEADERS_FILE = "headers.dat"
    const val HEADERS_CHECKSUM = "headers.checksum"
    const val HEADERS_METADATA = "headers.meta"
}

