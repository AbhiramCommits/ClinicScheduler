package com.clinicscheduler.appointment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Service-to-service client for the two provider-service slot state transitions used by booking.
 * The base URL and timeouts come from app.provider-service.* configuration.
 */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final RestClient restClient;

    public ProviderClient(RestClient providerRestClient) {
        this.restClient = providerRestClient;
    }

    public void reserve(Long slotId) {
        try {
            restClient.post()
                    .uri("/api/availability/{slotId}/reserve", slotId)
                    .headers(headers -> {
                        String authorization = currentAuthorization();
                        if (authorization != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict ex) {
            throw new SlotTakenException(slotId);
        } catch (RestClientException ex) {
            log.error("provider-service reserve failed for slot {}", slotId, ex);
            throw new ProviderUnavailableException("provider-service reserve failed", ex);
        }
    }

    public void release(Long slotId) {
        try {
            restClient.post()
                    .uri("/api/availability/{slotId}/release", slotId)
                    .headers(headers -> {
                        String authorization = currentAuthorization();
                        if (authorization != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                log.warn("provider-service reported slot {} not booked during release", slotId);
                return;
            }
            throw new ProviderUnavailableException("provider-service release failed", ex);
        } catch (RestClientException ex) {
            log.error("provider-service release failed for slot {}", slotId, ex);
            throw new ProviderUnavailableException("provider-service release failed", ex);
        }
    }

    /**
     * Propagates the caller's bearer token so provider-service sees the same authenticated user.
     */
    private static String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }
}
