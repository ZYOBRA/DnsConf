package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
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
        List<RewriteDto> existingRewrites = getExistingRewrites();
        newRewriteRequests.keySet().removeIf(excludeRedirectCheckService::shouldExclude);

        // NextDNS does not expose ownership. Never delete an unknown existing
        // entry merely because it disappeared from a source or exceeds our cap.
        Set<String> existingDomains = new HashSet<>();
        List<String> outdatedIds = new ArrayList<>();
        List<CreateRewriteDto> replacements = new ArrayList<>();
        for (RewriteDto existing : existingRewrites) {
            String domain = existing.name();
            // Preserve duplicate/ambiguous existing records rather than guessing.
            existingDomains.add(domain);
            CreateRewriteDto request = newRewriteRequests.get(domain);
            if (request != null && !request.content().equals(existing.content())
                    && existingRewrites.stream().filter(x -> x.name().equals(domain)).count() == 1) {
                outdatedIds.add(existing.id());
                replacements.add(request);
            }
        }

        // Replacements do not increase the number of existing records.
        // Reserve space for them first; new domains can use only remaining capacity.
        int available = Math.max(0, MAX_REWRITES - existingRewrites.size());
        List<CreateRewriteDto> candidates = newRewriteRequests.entrySet().stream()
                .filter(entry -> !existingDomains.contains(entry.getKey()))
                .sorted(Comparator.<Map.Entry<String, CreateRewriteDto>, Boolean>comparing(
                        entry -> !priority(entry.getKey())).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
        List<CreateRewriteDto> additions = candidates.stream().limit(available).toList();
        Log.common("NextDNS: existing=%s, replacements=%s, additions=%s, skipped=%s, target=%s"
                .formatted(existingRewrites.size(), replacements.size(), additions.size(),
                        candidates.size() - additions.size(), MAX_REWRITES));
        if (!outdatedIds.isEmpty()) {
            nextDnsRewriteClient.deleteRewritesByIds(outdatedIds);
        }
        List<CreateRewriteDto> result = new ArrayList<>(replacements);
        result.addAll(additions);
        return result;
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
