package io.yunxi.app.tenant;

import io.yunxi.definition.QuotaType;
import io.yunxi.runtime.tenant.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenantQuotaExceptionHandlerTest {

    @Test
    void mapsQuotaExceededExceptionTo429() {
        TenantQuotaExceptionHandler handler = new TenantQuotaExceptionHandler();
        QuotaExceededException ex = new QuotaExceededException("t1", QuotaType.DAILY_REQUEST, 100);
        ResponseEntity<Map<String, Object>> response = handler.handle(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("quota_exceeded");
        assertThat(body.get("tenant")).isEqualTo("t1");
        assertThat(body.get("type")).isEqualTo("DAILY_REQUEST");
        assertThat(body.get("limit")).isEqualTo(100L);
    }
}
