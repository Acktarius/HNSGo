# hnsd P2P Handshake Analysis

## Key Files for P2P Protocol

### 1. **pool.h** - Peer States and Structure
- `HSK_STATE_DISCONNECTED = 0`
- `HSK_STATE_CONNECTING = 2`
- `HSK_STATE_CONNECTED = 3`
- `HSK_STATE_READING = 4`
- `HSK_STATE_HANDSHAKE = 5` ← **Critical state for handshake**
- `HSK_STATE_DISCONNECTING = 6`

### 2. **msg.h** - Message Types
- `HSK_MSG_VERSION = 0`
- `HSK_MSG_VERACK = 1` ← **Empty payload (0 bytes)**
- `HSK_MSG_PING = 2`
- `HSK_MSG_PONG = 3`
- `HSK_MSG_GETHEADERS = 10`
- `HSK_MSG_HEADERS = 11`

### 3. **msg.c** - Message Serialization

#### Version Message Structure (hsk_version_msg_t):
```c
typedef struct {
  uint8_t cmd;              // HSK_MSG_VERSION (0)
  uint32_t version;         // HSK_PROTO_VERSION (1)
  uint64_t services;        // HSK_SERVICES (0)
  uint64_t time;            // Current timestamp
  hsk_netaddr_t remote;      // 88 bytes: time(8) + services(8) + addr(72)
  uint64_t nonce;           // Random nonce
  char agent[256];          // User agent string
  uint32_t height;          // Chain height
  uint8_t no_relay;         // 0 or 1 (boolean)
} hsk_version_msg_t;
```

#### Verack Message Structure (hsk_verack_msg_t):
```c
typedef struct {
  uint8_t cmd;              // HSK_MSG_VERACK (1)
  // NO PAYLOAD - verack is empty!
} hsk_verack_msg_t;
```

**Key Finding**: `hsk_verack_msg_write()` returns 0 - verack has **ZERO payload bytes**!

### 4. **pool.c** - Handshake Flow

#### Connection Flow (on_connect):
```c
on_connect(uv_connect_t *conn, int status) {
  // 1. TCP connection established
  peer->state = HSK_STATE_CONNECTED;
  
  // 2. Start reading from socket
  uv_read_start((uv_stream_t *)socket, alloc_buffer, after_read);
  peer->state = HSK_STATE_READING;
  
  // 3. If brontide (encrypted), wait for encryption handshake
  if (peer->brontide != NULL) {
    hsk_brontide_on_connect(peer->brontide);
    return; // Will call after_brontide_connect later
  }
  
  // 4. For plain TCP, immediately send version
  peer->state = HSK_STATE_HANDSHAKE;
  hsk_peer_send_version(peer);  // ← Send version immediately
}
```

#### Version Message Sending (hsk_peer_send_version):
```c
hsk_peer_send_version(hsk_peer_t *peer) {
  hsk_version_msg_t msg = { .cmd = HSK_MSG_VERSION };
  msg.version = HSK_PROTO_VERSION;      // 1
  msg.services = HSK_SERVICES;           // 0
  msg.time = hsk_timedata_now(&pool->td);
  hsk_addr_copy(&msg.remote.addr, &peer->addr);
  msg.nonce = hsk_nonce();
  strcpy(msg.agent, pool->user_agent);   // "/hnsd:0.1/"
  msg.height = (uint32_t)pool->chain.height;
  peer->version_time = hsk_now();        // Track timeout
  return hsk_peer_send(peer, (hsk_msg_t *)&msg);
}
```

#### Version Message Handling (hsk_peer_handle_version):
```c
hsk_peer_handle_version(hsk_peer_t *peer, const hsk_version_msg_t *msg) {
  // 1. Log received version
  hsk_peer_log(peer, "received version: %s (%u)\n", msg->agent, msg->height);
  peer->height = (int64_t)msg->height;
  strcpy(peer->agent, msg->agent);
  
  // 2. Update address manager
  hsk_timedata_add(&pool->td, &peer->addr, msg->time);
  hsk_addrman_mark_ack(&pool->am, &peer->addr, msg->services);
  
  // 3. Send VERACK immediately
  hsk_peer_send_verack(peer);  // ← Respond with verack
  
  // 4. Handshake complete - start syncing
  hsk_peer_send_sendheaders(peer);
  hsk_peer_send_getaddr(peer);
  hsk_peer_send_getheaders(peer, NULL);
}
```

#### Verack Sending (hsk_peer_send_verack):
```c
hsk_peer_send_verack(hsk_peer_t *peer) {
  hsk_peer_log(peer, "sending verack\n");
  hsk_version_msg_t msg = { .cmd = HSK_MSG_VERACK };  // Note: uses version_msg_t struct!
  return hsk_peer_send(peer, (hsk_msg_t *)&msg);
}
```

#### Verack Handling (hsk_peer_handle_verack):
```c
hsk_peer_handle_verack(hsk_peer_t *peer, const hsk_verack_msg_t *msg) {
  hsk_peer_log(peer, "received verack\n");
  peer->version_time = 0;  // Clear timeout
  // VERACK is boring, no need to respond.
  return HSK_SUCCESS;
}
```

### 5. **Message Framing (pool.c:hsk_peer_send)**

```c
hsk_peer_send(hsk_peer_t *peer, const hsk_msg_t *msg) {
  // 1. Calculate message size
  int msg_size = hsk_msg_size(msg);
  
  // 2. Build message frame
  uint8_t *buf = data;
  write_u32(&buf, HSK_MAGIC);        // 4 bytes: 0x5b6ef2d3
  write_u8(&buf, msg->cmd);          // 1 byte: command code
  write_u32(&buf, msg_size);        // 4 bytes: payload size
  hsk_msg_write(msg, &buf);          // Variable: payload
  
  // NO CHECKSUM! This is hnsd's simplified format.
  return hsk_peer_write(peer, data, size, true);
}
```

**Message Frame Format**:
```
[4 bytes: Magic] [1 byte: Command] [4 bytes: Size] [Variable: Payload]
```

### 6. **Timeout Handling**

```c
// Verack timeout: 10 seconds
if (peer->version_time && now > peer->version_time + 10) {
  hsk_peer_log(peer, "peer is stalling (verack)\n");
  hsk_peer_destroy(peer);
}
```

## Handshake Sequence

### For Plain TCP (Our Case):

1. **Client connects** → `HSK_STATE_CONNECTING`
2. **TCP connected** → `HSK_STATE_CONNECTED`
3. **Start reading** → `HSK_STATE_READING`
4. **Send VERSION** → `HSK_STATE_HANDSHAKE` + `hsk_peer_send_version()`
5. **Wait for peer's VERSION** → `hsk_peer_handle_version()`
6. **Send VERACK** → `hsk_peer_send_verack()` (in response to peer's version)
7. **Wait for peer's VERACK** → `hsk_peer_handle_verack()`
8. **Handshake complete** → Start syncing headers

### Key Observations:

1. **Verack has ZERO payload** - only command byte in message frame
2. **Both sides send version first**, then respond with verack
3. **Version timeout is 10 seconds** - if verack not received, disconnect
4. **Message framing**: Magic(4) + Command(1) + Size(4) + Payload(variable)
5. **No checksum** in hnsd's message format (unlike Bitcoin)

## Our Implementation Issues

Based on the logs showing "Connection reset" and "EOF while reading message header":

1. ✅ **Message framing is correct** (Magic + Command + Size + Payload)
2. ✅ **Version message format matches** (88-byte netaddr, correct field order)
3. ❓ **Verack handling** - Need to ensure we wait for peer's version before sending verack
4. ❓ **Timeout handling** - Should disconnect if verack not received within 10 seconds

## Recommendations

1. **Verify verack payload is empty** - Should be 0 bytes after message header
2. **Add version timeout** - Disconnect if verack not received within 10 seconds
3. **Log message sizes** - Verify verack message is exactly 9 bytes (4+1+4+0)
4. **Check peer state transitions** - Ensure we're in HANDSHAKE state during handshake

