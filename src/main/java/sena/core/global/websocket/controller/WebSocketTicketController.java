package sena.core.global.websocket.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sena.core.global.response.ResponseData;
import sena.core.global.websocket.service.WebSocketTicketService;
import sena.core.global.websocket.dto.WebSocketTicketResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
@Tag(name = "웹소켓")
public class WebSocketTicketController {

    private final WebSocketTicketService ticketService;

    @Operation(summary = "웹소켓 티켓 발급")
    @PostMapping("/ws-ticket")
    public ResponseData<WebSocketTicketResponse> issueTicket() {
        return ResponseData.success(ticketService.issueTicket());
    }
}
