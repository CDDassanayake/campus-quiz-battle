package com.quizbattle.client.ui;

import com.quizbattle.client.model.PlayerScore;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RankingPresentation {
    private RankingPresentation() { }
    public static String freshness(long revision, long received, long now) {
        if (revision == 0) { return "Waiting for live rankings…"; }
        long seconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(now - received));
        return seconds >= 5 ? "No recent feed update · " + seconds + "s ago" : "Latest UDP ranking · " + seconds + "s ago";
    }
    public static String personal(List<PlayerScore> scores, String username) {
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).username().equals(username)) {
                return "Your final score · " + scores.get(i).score() + "   /   Server listing position " + (i + 1);
            }
        }
        return "Your player is not listed in the final standings.";
    }
}
