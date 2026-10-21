package jp.komorebi.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** いたずら連投よけの、ごく簡単な回数制限（サーバーを再起動するとリセット） */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMillis) q.pollFirst();
            if (q.size() >= max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "少し時間をおいてからもう一度送ってね");
            q.addLast(now);
        }
    }
}
