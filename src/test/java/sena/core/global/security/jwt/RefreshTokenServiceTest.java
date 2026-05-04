package sena.core.global.security.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import sena.core.global.security.config.JwtConfig;
import sena.core.global.security.jwt.enums.TokenValidationResult;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RedisTemplate<String, String> redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private JwtConfig jwtConfig;

    @Mock
    private AccessTokenBlacklistService blacklistService;

    private RefreshTokenService refreshTokenService;

    private static final Long MEMBER_ID = 1L;
    private static final String REFRESH_TOKEN = "test-refresh-token";
    private static final String NEW_REFRESH_TOKEN = "new-refresh-token";

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        refreshTokenService = new RefreshTokenService(redis, jwtConfig, blacklistService);
    }

    @Test
    @DisplayName("락 획득 성공")
    void tryAcquireLock_success() {
        // given
        when(valueOps.setIfAbsent(eq("RT_LOCK:" + MEMBER_ID), eq("locked"), eq(5L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        // when
        boolean result = refreshTokenService.tryAcquireLock(MEMBER_ID);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("락 획득 실패 - 다른 요청이 락을 보유")
    void tryAcquireLock_fail() {
        // given
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        // when
        boolean result = refreshTokenService.tryAcquireLock(MEMBER_ID);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("락 해제")
    void releaseLock() {
        // when
        refreshTokenService.releaseLock(MEMBER_ID);

        // then
        verify(redis).delete("RT_LOCK:" + MEMBER_ID);
    }

    @Test
    @DisplayName("Refresh 토큰 저장 - 기존 토큰이 없는 경우")
    void saveRefreshToken_noExistingToken() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(null);
        when(jwtConfig.refreshTokenExpiration()).thenReturn(86400L);

        // when
        refreshTokenService.saveRefreshToken(MEMBER_ID, REFRESH_TOKEN);

        // then
        verify(valueOps).set(eq("RT:" + MEMBER_ID), eq(REFRESH_TOKEN), eq(86400L), eq(TimeUnit.SECONDS));
        verify(valueOps, never()).set(startsWith("RT_PREV:"), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("Refresh 토큰 저장 - 기존 토큰을 이전 토큰으로 이동")
    void saveRefreshToken_withExistingToken() {
        // given
        String oldToken = "old-refresh-token";
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(oldToken);
        when(jwtConfig.refreshTokenExpiration()).thenReturn(86400L);

        // when
        refreshTokenService.saveRefreshToken(MEMBER_ID, NEW_REFRESH_TOKEN);

        // then
        verify(valueOps).set(eq("RT_PREV:" + MEMBER_ID), eq(oldToken), eq(30L), eq(TimeUnit.SECONDS));
        verify(valueOps).set(eq("RT:" + MEMBER_ID), eq(NEW_REFRESH_TOKEN), eq(86400L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("토큰 검증 - 현재 토큰과 일치")
    void validate_currentToken() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(REFRESH_TOKEN);

        // when
        TokenValidationResult result = refreshTokenService.validate(MEMBER_ID, REFRESH_TOKEN);

        // then
        assertThat(result).isEqualTo(TokenValidationResult.CURRENT);
    }

    @Test
    @DisplayName("토큰 검증 - 이전 토큰과 일치 (30초 유예기간)")
    void validate_previousToken() {
        // given
        String previousToken = "previous-token";
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(NEW_REFRESH_TOKEN);
        when(valueOps.get("RT_PREV:" + MEMBER_ID)).thenReturn(previousToken);

        // when
        TokenValidationResult result = refreshTokenService.validate(MEMBER_ID, previousToken);

        // then
        assertThat(result).isEqualTo(TokenValidationResult.PREVIOUS);
    }

    @Test
    @DisplayName("토큰 검증 - 무효한 토큰 (재사용 공격 감지)")
    void validate_invalidToken_reuseDetected() {
        // given
        String invalidToken = "invalid-token";
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(REFRESH_TOKEN);
        when(valueOps.get("RT_PREV:" + MEMBER_ID)).thenReturn(null);

        // when
        TokenValidationResult result = refreshTokenService.validate(MEMBER_ID, invalidToken);

        // then
        assertThat(result).isEqualTo(TokenValidationResult.INVALID);
        verify(redis).delete("RT:" + MEMBER_ID);
        verify(redis).delete("RT_PREV:" + MEMBER_ID);
    }

    @Test
    @DisplayName("토큰 검증 - 저장된 토큰이 없는 경우")
    void validate_noSavedToken() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(null);
        when(valueOps.get("RT_PREV:" + MEMBER_ID)).thenReturn(null);

        // when
        TokenValidationResult result = refreshTokenService.validate(MEMBER_ID, REFRESH_TOKEN);

        // then
        assertThat(result).isEqualTo(TokenValidationResult.INVALID);
        verify(blacklistService, never()).blacklistAllAccess(anyLong());
    }

    @Test
    @DisplayName("현재 Refresh 토큰 조회")
    void getCurrentRefreshToken() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(REFRESH_TOKEN);

        // when
        String result = refreshTokenService.getCurrentRefreshToken(MEMBER_ID);

        // then
        assertThat(result).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Refresh 토큰 삭제")
    void deleteRefreshToken() {
        // when
        refreshTokenService.deleteRefreshToken(MEMBER_ID);

        // then
        verify(redis).delete("RT:" + MEMBER_ID);
        verify(redis).delete("RT_PREV:" + MEMBER_ID);
    }

    @Test
    @DisplayName("isValid - 유효한 토큰")
    void isValid_valid() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(REFRESH_TOKEN);

        // when
        boolean result = refreshTokenService.isValid(MEMBER_ID, REFRESH_TOKEN);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isValid - 무효한 토큰")
    void isValid_invalid() {
        // given
        when(valueOps.get("RT:" + MEMBER_ID)).thenReturn(null);
        when(valueOps.get("RT_PREV:" + MEMBER_ID)).thenReturn(null);

        // when
        boolean result = refreshTokenService.isValid(MEMBER_ID, "invalid-token");

        // then
        assertThat(result).isFalse();
    }
}
