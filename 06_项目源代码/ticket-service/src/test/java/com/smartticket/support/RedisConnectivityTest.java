package com.smartticket.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 第8天验收测试：Redis 接入可用。
 *
 * <p>两个用例守护两件不同的事：</p>
 * <ol>
 *   <li>配置是否被真正加载 —— {@code spring.data.redis} 前缀写错不会报错，
 *       只会静默使用默认值，必须靠断言才能发现；</li>
 *   <li>连接是否可用 —— 真写入、真读回、真删除。</li>
 * </ol>
 *
 * <p>依赖 Docker 容器 {@code smartticket-redis}（端口 6379）。容器未启动时本类失败是预期行为，
 * 因为 Redis 接入是第9天缓存功能的前提，不应该被静默跳过。</p>
 *
 * <p>测试 key 用独立前缀且用完即删，不污染第9天的业务缓存数据。</p>
 */
@SpringBootTest
class RedisConnectivityTest {

    @org.springframework.beans.factory.annotation.Value("${test.redis.port:6379}")
    private int expectedPort;

    @org.springframework.beans.factory.annotation.Value("${test.redis.host:127.0.0.1}")
    private String expectedHost;

    /** 测试专用 key 前缀，与后续业务缓存 key 区分开 */
    private static final String TEST_KEY_PREFIX = "smartticket:test:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @Test
    @DisplayName("application.yml 的 spring.data.redis 配置被加载（守护前缀写错）")
    void redisConfigIsLoadedFromYaml() {
        assertEquals(expectedHost, redisProperties.getHost());
        assertEquals(expectedPort, redisProperties.getPort());
        assertEquals(Integer.valueOf(0), redisProperties.getDatabase());
        // timeout 是本项目显式声明的非默认值：若前缀误写成 Boot 2 的 spring.redis，
        // 这里读到的是 null，本断言失败 —— 这是该用例存在的唯一理由
        assertEquals(Duration.ofSeconds(3), redisProperties.getTimeout());
    }

    @Test
    @DisplayName("能连上 Redis 并完成 写入 → 读回 → 删除")
    void canWriteReadAndDeleteKey() {
        String key = TEST_KEY_PREFIX + "connectivity";
        String value = "ok-" + System.currentTimeMillis();

        stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30));

        String readBack = stringRedisTemplate.opsForValue().get(key);
        assertEquals(value, readBack, "写入后应立即读回相同值");

        stringRedisTemplate.delete(key);
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key)), "删除后 key 应不存在");
    }

}
