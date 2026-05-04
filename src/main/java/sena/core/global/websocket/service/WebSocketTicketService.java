package sena.core.global.websocket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import sena.core.content.member.domain.Member;
import sena.core.global.request.RequestContext;
import sena.core.global.websocket.dto.TicketData;
import sena.core.global.websocket.dto.WebSocketTicketResponse;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketTicketService {

    private static final String TICKET_PREFIX = "ws:ticket:";
    private static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final RequestContext requestContext;

    public WebSocketTicketResponse issueTicket() {
        Member actor = requestContext.getActor();
        String ticket = UUID.randomUUID().toString();
        String key = TICKET_PREFIX + ticket;

        String role = actor.getRole() != null ? actor.getRole().name() : "USER";
        String value = actor.getId() + ":" + actor.getNickname() + ":" + role;
        redisTemplate.opsForValue().set(key, value, TICKET_TTL);
        log.debug("WebSocket ticket issued: memberId={}", actor.getId());

        return new WebSocketTicketResponse(ticket);
    }

    public TicketData validateAndConsume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }

        String key = TICKET_PREFIX + ticket;
        String value = redisTemplate.opsForValue().getAndDelete(key);

        if (value == null) {
            log.warn("Invalid or expired WebSocket ticket");
            return null;
        }

        try {
            String[] parts = value.split(":", 3);
            Long memberId = Long.parseLong(parts[0]);
            String nickname = parts[1];
            String role = parts.length > 2 ? parts[2] : "USER";
            log.debug("WebSocket ticket validated: memberId={}", memberId);
            return new TicketData(memberId, nickname, role);
        } catch (Exception e) {
            log.error("Invalid ticket data: {}", value);
            return null;
        }
    }
}
