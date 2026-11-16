# Campus Quiz Battle - Wire Protocol (v1)

Owner: **M2**. Any change must be announced to the whole team and merged through a `common/` contract-change PR.

There are three channels. Each uses a different transport on purpose.

| Channel | Transport | Port (default) | Used for |
|---|---|---|---|
| Authentication | TCP + **TLS** (`SSLServerSocket`) | 5443 | register, login, receive token |
| Game | TCP (**NIO** server) | 5050 | rooms, questions, answers, results |
| Discovery | **UDP** broadcast | 5051 | find servers on the LAN |
| Live feed | **UDP** broadcast/multicast | 5052 | countdown + leaderboard (loss tolerant) |

## 1. Text framing (TLS channel and game channel)

- Encoding: UTF-8. One message per line, terminated by `\n`. Maximum 4096 bytes per line.
- Fields are separated by `|`. First field is the message type.
- Escaping inside a field: `\\` for a backslash, `\|` for a pipe, `\n` for a newline character.
- Usernames match `[A-Za-z0-9_]{3,16}`. Room names match `[A-Za-z0-9_ ]{1,24}`. (So `:` and `;` are safe delimiters in lists.)

## 2. TLS authentication channel (M5)

| Direction | Message | Meaning |
|---|---|---|
| C to S | `REGISTER|username|password` | Create an account |
| C to S | `LOGIN|username|password` | Authenticate |
| S to C | `OK` | Register succeeded |
| S to C | `TOKEN|token` | Login succeeded |
| S to C | `ERROR|code|text` | `BAD_CREDENTIALS`, `USER_EXISTS`, `LOCKED`, `BAD_MESSAGE` |

Token format: `base64url(username).expiryEpochSeconds.base64url(HMAC-SHA256(secret, username + "." + expiry))`.
Passwords are only ever sent inside TLS and are stored only as salted hashes.

## 3. Game channel (M1 server, M2 client, M4 logic)

The first message after connecting MUST be `HELLO|token` within 5 seconds, otherwise the server closes the connection.

### Client to Server

| Message | Meaning |
|---|---|
| `HELLO|token` | Authenticate this connection |
| `PING` | Keep-alive (answered by the gateway itself) |
| `LIST_ROOMS` | Ask for open rooms |
| `CREATE_ROOM|roomName|questionCount` | Create a room and become host |
| `JOIN|roomId` | Join a room in the LOBBY state |
| `LEAVE` | Leave the current room |
| `START` | Host starts the game |
| `ANSWER|questionId|choiceIndex` | Answer the current question (0-3). Only the first answer counts |

### Server to Client

| Message | Meaning |
|---|---|
| `WELCOME|username` | HELLO accepted |
| `PONG` | Reply to PING |
| `ERROR|code|text` | See error codes below |
| `ROOMS|id:name:players:state;id:name:players:state` | Reply to LIST_ROOMS. State is `LOBBY`, `RUNNING` or `FINISHED` |
| `JOINED|roomId|roomName|isHost` | Join or create succeeded. `isHost` is `true` or `false` |
| `PLAYER_JOINED|username` / `PLAYER_LEFT|username` | Lobby and game roster changes |
| `QUESTION|questionId|index|total|text|optA|optB|optC|optD|timeLimitSec` | New question |
| `RESULT|questionId|correctIndex|pointsAwarded|totalScore` | Sent privately to each player when the round closes |
| `GAME_OVER|winner|name:score;name:score` | Final standings (reliable, over TCP) |

### Error codes
`BAD_TOKEN`, `BAD_MESSAGE`, `NOT_AUTHENTICATED`, `ALREADY_CONNECTED`, `ROOM_NOT_FOUND`, `ROOM_FULL`, `NOT_IN_ROOM`, `NOT_HOST`, `GAME_RUNNING`, `ALREADY_ANSWERED`, `TOO_LATE`.

### Typical session
```
C: HELLO|<token>            S: WELCOME|alice
C: CREATE_ROOM|Networks|5   S: JOINED|r1|Networks|true
   (bob joins)              S: PLAYER_JOINED|bob
C: START                    S: QUESTION|3|1|5|What does DNS translate?|...|15
C: ANSWER|3|1               S: RESULT|3|1|130|130
   ...                      S: GAME_OVER|alice|alice:640;bob:510
```

## 4. UDP datagrams (M3). Maximum 512 bytes, UTF-8, same `|` separator

| Datagram | Direction | Meaning |
|---|---|---|
| `QB_DISCOVER|1` | client broadcast to port 5051 | Who is a Quiz Battle server? |
| `QB_SERVER|1|serverName|gatewayPort|tlsPort` | server reply (unicast) | I am a server (host = sender address) |
| `QB_CD|seq|roomId|secondsLeft` | server to feed port | Countdown tick |
| `QB_LB|seq|roomId|name:score;name:score` | server to feed port | Live leaderboard |

`seq` is a per-sender increasing number. Receivers ignore a datagram whose `seq` is lower than the last one seen for that room (UDP can reorder and lose packets, so the feed must never be required for correctness).
