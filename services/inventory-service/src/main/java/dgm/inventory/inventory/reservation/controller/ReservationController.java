package dgm.inventory.inventory.reservation.controller;

import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.kit.json.Json;
import dgm.kit.time.Timestamps;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренние операции резерва: {@code reserveKeys} (OP-51). Вызывает только {@code order-service}: список вызывающих проверяет
 * фильтр каркаса по сертификату клиента (mTLS) и правилам маршрутов, ключ идемпотентности ведёт охранник каркаса (в запросе
 * саги он равен идентификатору заказа). Ответы с {@code Cache-Control: no-store}, значений ключей в них нет.
 */
@RestController
public class ReservationController {

    private static final String BASE = "/internal/v1/reservations";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping(BASE)
    public ResponseEntity<String> reserve(HttpServletRequest request) throws IOException {
        ReservationBodies.ReserveRequest body = ReservationBodies.read(request);
        Reservation reservation = service.reserve(body.orderId(), body.productId(), body.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, BASE + "/" + reservation.id()).body(Json.write(view(reservation)));
    }

    /** Резерв для ответа (схема {@code Reservation}). */
    static Map<String, Object> view(Reservation reservation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservationId", reservation.id().toString());
        body.put("orderId", reservation.orderId().toString());
        body.put("productId", reservation.productId().toString());
        body.put("quantity", reservation.quantity());
        body.put("status", reservation.status());
        body.put("expiresAt", Timestamps.format(reservation.expiresAt()));
        return body;
    }
}
