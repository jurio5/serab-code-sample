package sena.core.global.security.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import sena.core.global.security.config.JwtConfig;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AccessTokenBlacklistService {

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtConfig jwtConfig;

    private static final String BLACKLIST_PREFIX = "BL:";
    private static final String ALL_BLACKLIST_PREFIX = "BL_ALL:";

    public void blacklist(String accessToken) {
        long ttlSeconds = jwtConfig.accessTokenExpiration();

        redisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + accessToken,
                "logout",
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }

    public void blacklistAllAccess(Long memberId) {
        redisTemplate.opsForValue().set(
                ALL_BLACKLIST_PREFIX + memberId,
                "true",
                jwtConfig.refreshTokenExpiration(),
                TimeUnit.SECONDS
        );
    }

    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(BLACKLIST_PREFIX + accessToken)
        );
    }

    public boolean isAllAccessBlacklisted(Long memberId) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(ALL_BLACKLIST_PREFIX + memberId)
        );
    }
}