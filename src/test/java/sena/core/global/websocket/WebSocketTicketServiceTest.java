package sena.core.global.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import sena.core.content.member.domain.Member;
import sena.core.global.request.RequestContext;
import sena.core.global.websocket.dto.TicketData;
import sena.core.global.websocket.dto.WebSocketTicketResponse;
import sena.core.global.websocket.service.WebSocketTicketService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketTicketServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private RequestContext requestContext;

    @Mock
    private Member member;

    private WebSocketTicketService ticketService;

    private static final Long MEMBER_ID = 1L;
    private static final String NICKNAME = "테스터";
    private static final String ROLE = "USER";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ticketService = new WebSocketTicketService(redisTemplate, requestContext);
    }

    @Test
    @DisplayName("티켓 발급 성공")
    void issueTicket_success() {
        // given
        when(requestContext.getActor()).thenReturn(member);
        when(member.getId()).thenReturn(MEMBER_ID);
        when(member.getNickname()).thenReturn(NICKNAME);

        // when
        WebSocketTicketResponse response = ticketService.issueTicket();

        // then
        assertThat(response).isNotNull();
        assertThat(response.ticket()).isNotBlank();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).startsWith("ws:ticket:");
        assertThat(valueCaptor.getValue()).isEqualTo(MEMBER_ID + ":" + NICKNAME + ":" + ROLE);
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("티켓 검증 성공 - 유효한 티켓")
    void validateAndConsume_validTicket() {
        // given
        String ticket = "valid-ticket-uuid";
        when(valueOps.getAndDelete("ws:ticket:" + ticket)).thenReturn(MEMBER_ID + ":" + NICKNAME);

        // when
        TicketData result = ticketService.validateAndConsume(ticket);

        // then
        assertThat(result).isNotNull();
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.nickname()).isEqualTo(NICKNAME);
        verify(valueOps).getAndDelete("ws:ticket:" + ticket);
    }

    @Test
    @DisplayName("티켓 검증 실패 - null 티켓")
    void validateAndConsume_nullTicket() {
        // when
        TicketData result = ticketService.validateAndConsume(null);

        // then
        assertThat(result).isNull();
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("티켓 검증 실패 - 빈 티켓")
    void validateAndConsume_blankTicket() {
        // when
        TicketData result = ticketService.validateAndConsume("   ");

        // then
        assertThat(result).isNull();
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("티켓 검증 실패 - 만료되거나 없는 티켓")
    void validateAndConsume_expiredTicket() {
        // given
        String ticket = "expired-ticket";
        when(valueOps.getAndDelete("ws:ticket:" + ticket)).thenReturn(null);

        // when
        TicketData result = ticketService.validateAndConsume(ticket);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("티켓 검증 실패 - 잘못된 데이터 형식")
    void validateAndConsume_invalidDataFormat() {
        // given
        String ticket = "ticket-with-bad-data";
        when(valueOps.getAndDelete("ws:ticket:" + ticket)).thenReturn("not-a-valid-format");

        // when
        TicketData result = ticketService.validateAndConsume(ticket);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("티켓은 일회용 - getAndDelete로 삭제됨")
    void validateAndConsume_ticketConsumed() {
        // given
        String ticket = "one-time-ticket";
        when(valueOps.getAndDelete("ws:ticket:" + ticket)).thenReturn(MEMBER_ID + ":" + NICKNAME);

        // when
        ticketService.validateAndConsume(ticket);

        // then
        verify(valueOps, times(1)).getAndDelete("ws:ticket:" + ticket);
    }
}
