package com.linyi.pig.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VectorStoreCache {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "rag:query:";
    private static final long CACHE_EXPIRE_SECONDS = 300;

    public List<Document> similaritySearchWithCache(String query, int topK) {
        String cacheKey = CACHE_PREFIX + Math.abs(query.hashCode());

        try {
            @SuppressWarnings("unchecked")
            List<Document> cached = (List<Document>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.info("RAG缓存命中: key={}, 文档数={}", cacheKey, cached.size());
                return cached.subList(0, Math.min(topK, cached.size()));
            }
        } catch (Exception e) {
            log.warn("RAG缓存读取失败: {}, 原因: {}", cacheKey, e.getMessage());
        }

        long startTime = System.nanoTime();
        List<Document> result = vectorStore.similaritySearch(query);
        long retrievalTime = System.nanoTime() - startTime;

        try {
            redisTemplate.opsForValue().set(cacheKey, result, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
            log.info("RAG缓存写入: key={}, 文档数={}, 检索耗时={}ms",
                    cacheKey, result.size(), retrievalTime / 1_000_000);
        } catch (Exception e) {
            log.warn("RAG缓存写入失败: {}, 原因: {}", cacheKey, e.getMessage());
        }

        return result.subList(0, Math.min(topK, result.size()));
    }

    public void clearCache(String query) {
        String cacheKey = CACHE_PREFIX + Math.abs(query.hashCode());
        try {
            redisTemplate.delete(cacheKey);
            log.info("RAG缓存清除: key={}", cacheKey);
        } catch (Exception e) {
            log.warn("RAG缓存清除失败: {}", e.getMessage());
        }
    }

    public void clearAllCache() {
        try {
            var keys = redisTemplate.keys(CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("RAG缓存清除完毕: 清除 {} 条缓存", keys.size());
            }
        } catch (Exception e) {
            log.warn("RAG缓存全部清除失败: {}", e.getMessage());
        }
    }
}