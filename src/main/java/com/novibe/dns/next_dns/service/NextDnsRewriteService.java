package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.exception.ProfileSetupException;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {

    private static final int MAX_REWRITES = 480;
    private static final Set<String> PRIORITY_DOMAINS = Set.of(
            "chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com",
            "claude.ai", "anthropic.com", "gemini.google.com",
            "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com");

    private static boolean priority(String domain) {
        return PRIORITY_DOMAINS.stream().anyMatch(root -> domain.equals(root) || domain.endsWith("." + root));
    }

    private final NextDnsRewriteClient nextDnsRewriteClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;

    public Map<String, CreateRewriteDto> buildNewRewrites(List<BypassRoute> overrides) {
        Map<String, CreateRewriteDto> rewriteDtos = new HashMap<>();
        overrides.forEach(route -> rewriteDtos.putIfAbsent(route.website(), new CreateRewriteDto(route.website(), route.ip())));
        return rewriteDtos;
    }

    public List<CreateRewriteDto> cleanupOutdatedAndExcluded(Map<String, CreateRewriteDto> newRewriteRequests) {
        // This profile is managed exclusively by DnsConf.
        if (newRewriteRequests.isEmpty()) {
            throw new ProfileSetupException("Empty redirect list; refusing to modify NextDNS");
        }
        Map<String, CreateRewriteDto> desired = new HashMap<>();
        newRewriteRequests.entrySet().stream()
                .filter(entry -> !excludeRedirectCheckService.shouldExclude(entry.getKey()))
                .sorted(Comparator.<Map.Entry<String, CreateRewriteDto>, Boolean>comparing(
                        entry -> !priority(entry.getKey())).thenComparing(Map.Entry::getKey))
                .limit(MAX_REWRITES)
                .forEachOrdered(entry -> desired.put(entry.getKey(), entry.getValue()));
        if (desired.isEmpty()) {
            throw new ProfileSetupException("No eligible redirects; refusing to modify NextDNS");
        }

        List<RewriteDto> existingRewrites = getExistingRewrites();
        Set<String> retained = new HashSet<>();
        List<String> removeIds = new ArrayList<>();
        for (RewriteDto existing : existingRewrites) {
            CreateRewriteDto request = desired.get(existing.name());
            if (request != null && request.content().equals(existing.content())
                    && retained.add(existing.name())) {
                continue;
            }
            removeIds.add(existing.id());
        }
        List<CreateRewriteDto> additions = desired.entrySet().stream()
                .filter(entry -> !retained.contains(entry.getKey()))
                .sorted(Comparator.<Map.Entry<String, CreateRewriteDto>, Boolean>comparing(
                        entry -> !priority(entry.getKey())).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
        Log.common("NextDNS: source=%s, target=%s, retained=%s, delete=%s, create=%s, omitted=%s"
                .formatted(newRewriteRequests.size(), desired.size(), retained.size(),
                        removeIds.size(), additions.size(), newRewriteRequests.size() - desired.size()));
        if (!removeIds.isEmpty()) {
            nextDnsRewriteClient.deleteRewritesByIds(removeIds);
        }
        return additions;
    }

    public List<RewriteDto> getExistingRewrites() {
        Log.io("Fetching existing rewrites from NextDNS");
        return nextDnsRewriteClient.fetchRewrites();
    }

    public void saveRewrites(List<CreateRewriteDto> createRewriteDtos) {
        Log.io("Saving %s new rewrites to NextDNS...".formatted(createRewriteDtos.size()));
        nextDnsRewriteClient.saveRewrites(createRewriteDtos);
    }

    public void removeAll() {
        Log.io("Fetching existing rewrites from NextDNS");
        List<RewriteDto> list = nextDnsRewriteClient.fetchRewrites();
        List<String> ids = list.stream().map(RewriteDto::id).toList();
        Log.io("Removing rewrites from NextDNS");
        nextDnsRewriteClient.deleteRewritesByIds(ids);
    }

}
