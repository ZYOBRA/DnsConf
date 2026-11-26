package com.novibe.dns.next_dns.service;

import com.novibe.common.exception.ProfileSetupException;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NextDnsRewriteServiceTest {
    private final NextDnsRewriteClient client = mock(NextDnsRewriteClient.class);
    private final ExcludeRedirectCheckService exclusions = mock(ExcludeRedirectCheckService.class);
    private final NextDnsRewriteService service = new NextDnsRewriteService(client, exclusions);

    @Test
    void capsTargetAndPrioritizesChatGpt() {
        Map<String, CreateRewriteDto> desired = new HashMap<>();
        for (int i = 0; i < 600; i++) {
            String domain = "a" + i + ".example.com";
            desired.put(domain, new CreateRewriteDto(domain, "1.2.3.4"));
        }
        desired.put("chatgpt.com", new CreateRewriteDto("chatgpt.com", "1.2.3.4"));
        when(client.fetchRewrites()).thenReturn(List.of(new RewriteDto("old", "obsolete.example.com", "1.2.3.4")));
        var additions = service.cleanupOutdatedAndExcluded(desired);
        assertEquals(480, additions.size());
        assertEquals("chatgpt.com", additions.getFirst().name());
        verify(client).deleteRewritesByIds(List.of("old"));
    }

    @Test
    void retainsMatchingAndRemovesDuplicateChangedAndExcluded() {
        when(exclusions.shouldExclude("excluded.example.com")).thenReturn(true);
        when(client.fetchRewrites()).thenReturn(List.of(
                new RewriteDto("keep", "chatgpt.com", "1.2.3.4"),
                new RewriteDto("duplicate", "chatgpt.com", "1.2.3.4"),
                new RewriteDto("changed", "openai.com", "1.1.1.1"),
                new RewriteDto("excluded", "excluded.example.com", "1.2.3.4")));
        var additions = service.cleanupOutdatedAndExcluded(Map.of(
                "chatgpt.com", new CreateRewriteDto("chatgpt.com", "1.2.3.4"),
                "openai.com", new CreateRewriteDto("openai.com", "1.2.3.4"),
                "excluded.example.com", new CreateRewriteDto("excluded.example.com", "1.2.3.4")));
        assertEquals(1, additions.size());
        assertEquals("openai.com", additions.getFirst().name());
        assertEquals("1.2.3.4", additions.getFirst().content());
        verify(client).deleteRewritesByIds(List.of("duplicate", "changed", "excluded"));
    }

    @Test
    void emptyInputCannotDeleteRecords() {
        assertThrows(ProfileSetupException.class, () -> service.cleanupOutdatedAndExcluded(Map.of()));
        verifyNoInteractions(client);
    }

    @Test
    void entirelyExcludedInputCannotDeleteRecords() {
        when(exclusions.shouldExclude("example.com")).thenReturn(true);
        assertThrows(ProfileSetupException.class, () -> service.cleanupOutdatedAndExcluded(
                Map.of("example.com", new CreateRewriteDto("example.com", "1.2.3.4"))));
        verifyNoInteractions(client);
    }
}
