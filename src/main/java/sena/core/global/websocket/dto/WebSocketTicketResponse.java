package sena.core.global.websocket.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "웹소켓 티켓 응답 DTO")
public record WebSocketTicketResponse(
        @Schema(description = "웹소켓 연결에 사용할 일회용 티켓 값") String ticket) {
}
