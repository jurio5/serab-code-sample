package sena.core.global.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import sena.core.global.security.config.JwtConfig;
import sena.core.global.security.jwt.enums.TokenValidationResult;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RedisTemplate<String, String> redis;
    private final JwtConfig jwtConfig;
    private final AccessTokenBlacklistService accessTokenBlacklistService;

    private static final String RT_PREFIX = "RT:";
    private static final String RT_PREV_PREFIX = "RT_PREV:";
    private static final String RT_LOCK_PREFIX = "RT_LOCK:";
    private static final int PREVIOUS_TOKEN_GRACE_PERIOD_SECONDS = 30;
    private static final int LOCK_TIMEOUT_SECONDS = 5;

    public boolean tryAcquireLock(Long memberId) {
        String lockKey = RT_LOCK_PREFIX + memberId;
        Boolean acquired = redis.opsForValue().setIfAbsent(
                lockKey,
                "locked",
                LOCK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseLock(Long memberId) {
        redis.delete(RT_LOCK_PREFIX + memberId);
    }

    public void saveRefreshToken(Long memberId, String refreshToken) {
        String currentToken = redis.opsForValue().get(RT_PREFIX + memberId);
        if (currentToken != null) {
            redis.opsForValue().set(
                    RT_PREV_PREFIX + memberId,
                    currentToken,
                    PREVIOUS_TOKEN_GRACE_PERIOD_SECONDS,
                    TimeUnit.SECONDS);
        }

        redis.opsForValue().set(
                RT_PREFIX + memberId,
                refreshToken,
                jwtConfig.refreshTokenExpiration(),
                TimeUnit.SECONDS);
    }

    public TokenValidationResult validate(Long memberId, String refreshToken) {
        String saved = redis.opsForValue().get(RT_PREFIX + memberId);
        log.debug("validate() - memberId: {}, saved: {}, incoming: {}",
                memberId,
                saved != null ? saved.substring(0, Math.min(20, saved.length())) + "..." : "null",
                refreshToken.substring(0, Math.min(20, refreshToken.length())) + "...");

        if (refreshToken.equals(saved)) {
            return TokenValidationResult.CURRENT;
        }

        String previous = redis.opsForValue().get(RT_PREV_PREFIX + memberId);
        if (refreshToken.equals(previous)) {
            log.debug("동시 요청으로 이전 토큰 사용. memberId={}", memberId);
            return TokenValidationResult.PREVIOUS;
        }

        log.warn("validate() INVALID - memberId: {}, savedExists: {}, prevExists: {}",
                memberId, saved != null, previous != null);
        if (saved != null) {
            onReuseDetected(memberId);
        }
        return TokenValidationResult.INVALID;
    }

    public String getCurrentRefreshToken(Long memberId) {
        return redis.opsForValue().get(RT_PREFIX + memberId);
    }

    public boolean isValid(Long memberId, String refreshToken) {
        return validate(memberId, refreshToken) != TokenValidationResult.INVALID;
    }

    public void deleteRefreshToken(Long memberId) {
        redis.delete(RT_PREFIX + memberId);
        redis.delete(RT_PREV_PREFIX + memberId);
    }

    private void onReuseDetected(Long memberId) {
        deleteRefreshToken(memberId);

        log.warn("Refresh Token 불일치 감지 → 토큰 삭제 (재로그인 필요). memberId={}", memberId);
    }
}
