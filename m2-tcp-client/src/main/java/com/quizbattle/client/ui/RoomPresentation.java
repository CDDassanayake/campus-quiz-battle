package com.quizbattle.client.ui;

import com.quizbattle.client.model.RoomInfo;
import java.util.List;
import java.util.Locale;

public final class RoomPresentation {
    private RoomPresentation() { }
    public static boolean joinable(RoomInfo room) { return room.status() == RoomInfo.Status.LOBBY; }
    public static List<RoomInfo> filter(List<RoomInfo> rooms, String search, String state) {
        String term = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        return rooms.stream().filter(room -> room.name().toLowerCase(Locale.ROOT).contains(term) || room.id().toLowerCase(Locale.ROOT).contains(term))
                .filter(room -> state == null || state.equals("All rooms") || room.status().name().equals(state)).toList();
    }
}
