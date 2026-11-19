package com.quizbattle.common;

/**
 * All message types in the wire protocol.
 * See docs/PROTOCOL.md for the full protocol specification.
 */
public enum MessageType {
    // TLS authentication channel (M5)
    REGISTER,
    LOGIN,
    OK,
    TOKEN,
    
    // Game channel - Client to Server
    HELLO,
    PING,
    LIST_ROOMS,
    CREATE_ROOM,
    JOIN,
    LEAVE,
    START,
    ANSWER,
    
    // Game channel - Server to Client
    WELCOME,
    PONG,
    ERROR,
    ROOMS,
    JOINED,
    PLAYER_JOINED,
    PLAYER_LEFT,
    QUESTION,
    RESULT,
    GAME_OVER
}
