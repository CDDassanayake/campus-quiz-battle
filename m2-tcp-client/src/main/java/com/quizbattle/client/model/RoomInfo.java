package com.quizbattle.client.model;

public record RoomInfo(String id, String name, int playerCount, Status status) {
    public enum Status { LOBBY, RUNNING, FINISHED }
}
