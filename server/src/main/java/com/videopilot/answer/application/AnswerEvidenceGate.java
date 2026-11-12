package com.videopilot.answer.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AnswerEvidenceGate {

    private static final Pattern TOKEN_RUN =
            Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]{2,}");

    private final int maxClaims;
    private final int maxClaimCharacters;
    private final int maxCitationsPerClaim;
    private final int maxQuoteCharacters;

    public AnswerEvidenceGate(
            int maxClaims, int maxClaimCharacters, int maxCitationsPerClaim, int maxQuoteCharacters) {
        if (maxClaims < 1 || maxClaimCharacters < 1 || maxCitationsPerClaim < 1 || maxQuoteCharacters < 1) {
            throw new IllegalArgumentException("answer evidence limits must be positive");
        }
        this.maxClaims = maxClaims;
        this.maxClaimCharacters = maxClaimCharacters;
        this.maxCitationsPerClaim = maxCitationsPerClaim;
        this.maxQuoteCharacters = maxQuoteCharacters;
    }

    public ValidationResult validate(Candidate candidate, List<EvidenceSource> evidence) {
        Objects.requireNonNull(candidate, "candidate");
        List<EvidenceSource> sources = List.copyOf(evidence);
        if (!candidate.answerable() || candidate.claims().isEmpty()) {
            return ValidationResult.rejected("NO_SUFFICIENT_EVIDENCE");
        }
        if (candidate.claims().size() > maxClaims) {
            return ValidationResult.rejected("CITATION_VALIDATION_FAILED");
        }
        List<VerifiedClaim> verified = new ArrayList<>();
        for (CandidateClaim claim : candidate.claims()) {
            if (claim.text() == null || claim.text().isBlank() || claim.text().length() > maxClaimCharacters
                    || claim.citations().isEmpty() || claim.citations().size() > maxCitationsPerClaim) {
                return ValidationResult.rejected("CITATION_VALIDATION_FAILED");
            }
            List<VerifiedCitation> citations = new ArrayList<>();
            for (CandidateCitation citation : claim.citations()) {
                List<VerifiedCitation> aligned = align(citation, sources);
                if (aligned.isEmpty()) {
                    return ValidationResult.rejected("CITATION_VALIDATION_FAILED");
                }
                citations.addAll(aligned);
            }
            if (!supported(claim.text(), citations)) {
                return ValidationResult.rejected("CITATION_VALIDATION_FAILED");
            }
            verified.add(new VerifiedClaim(claim.text().trim(), citations));
        }
        return new ValidationResult(true, List.copyOf(verified), null);
    }

    private List<VerifiedCitation> align(CandidateCitation citation, List<EvidenceSource> sources) {
        if (citation.evidenceId() == null || citation.evidenceId().isBlank()
                || citation.quote() == null || citation.quote().isBlank()
                || citation.quote().length() > maxQuoteCharacters) return List.of();
        EvidenceSource declared = sources.stream()
                .filter(EvidenceSource::selectable)
                .filter(source -> source.evidenceId().equals(citation.evidenceId()))
                .findFirst().orElse(null);
        if (declared == null) return List.of();
        List<EvidenceSource> neighborhood = sources.stream()
                .filter(source -> source.chunkId().equals(declared.chunkId()))
                .filter(source -> source.channel().equals(declared.channel()))
                .filter(source -> Math.abs(source.ordinal() - declared.ordinal()) <= 1)
                .sorted(Comparator.comparingInt(EvidenceSource::ordinal))
                .toList();
        for (EvidenceSource source : neighborhood) {
            String type = alignment(citation.quote(), source.text());
            if (type != null) {
                return List.of(new VerifiedCitation(
                        citation.evidenceId(), source.segmentId(), source.startMs(), source.endMs(),
                        source.channel(), citation.quote().trim(), type));
            }
        }
        for (int index = 0; index + 1 < neighborhood.size(); index++) {
            EvidenceSource left = neighborhood.get(index);
            EvidenceSource right = neighborhood.get(index + 1);
            if (right.ordinal() != left.ordinal() + 1) continue;
            List<VerifiedCitation> split = alignAcrossBoundary(citation, left, right);
            if (!split.isEmpty()) return split;
        }
        return List.of();
    }

    private List<VerifiedCitation> alignAcrossBoundary(
            CandidateCitation citation, EvidenceSource left, EvidenceSource right) {
        String quote = citation.quote().trim();
        for (int offset = quote.offsetByCodePoints(0, 1);
                offset < quote.length();
                offset = quote.offsetByCodePoints(offset, 1)) {
            String leftQuote = quote.substring(0, offset).trim();
            String rightQuote = quote.substring(offset).trim();
            if (normalize(leftQuote).length() < 2 || normalize(rightQuote).length() < 2) continue;
            String leftType = strictAlignment(leftQuote, left.text());
            String rightType = strictAlignment(rightQuote, right.text());
            if (leftType != null && rightType != null) {
                return List.of(
                        verified(citation.evidenceId(), left, leftQuote, leftType),
                        verified(citation.evidenceId(), right, rightQuote, rightType));
            }
        }
        return List.of();
    }

    private VerifiedCitation verified(
            String originalEvidenceId, EvidenceSource source, String quote, String alignmentType) {
        return new VerifiedCitation(
                originalEvidenceId, source.segmentId(), source.startMs(), source.endMs(),
                source.channel(), quote, alignmentType);
    }

    private String strictAlignment(String quote, String source) {
        if (source.contains(quote)) return "EXACT";
        String needle = normalize(quote);
        return !needle.isEmpty() && normalize(source).contains(needle) ? "NORMALIZED" : null;
    }

    private String alignment(String quote, String source) {
        if (source.contains(quote.trim())) return "EXACT";
        String needle = normalize(quote);
        String haystack = normalize(source);
        if (needle.isEmpty()) return null;
        if (haystack.contains(needle)) return "NORMALIZED";
        if (needle.length() < 8) return null;
        int distance = Math.max(1, Math.min(6, needle.length() / 10));
        int min = Math.max(1, needle.length() - distance);
        int max = Math.min(haystack.length(), needle.length() + distance);
        for (int length = min; length <= max; length++) {
            for (int start = 0; start + length <= haystack.length(); start++) {
                if (boundedDistance(needle, haystack.substring(start, start + length), distance) <= distance) {
                    return "APPROXIMATE";
                }
            }
        }
        return null;
    }

    private int boundedDistance(String left, String right, int limit) {
        if (Math.abs(left.length() - right.length()) > limit) return limit + 1;
        int[] previous = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            current[0] = leftIndex;
            int rowMinimum = current[0];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitution = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
                current[rightIndex] = Math.min(substitution,
                        Math.min(previous[rightIndex] + 1, current[rightIndex - 1] + 1));
                rowMinimum = Math.min(rowMinimum, current[rightIndex]);
            }
            if (rowMinimum > limit) return limit + 1;
            previous = current;
        }
        return previous[right.length()];
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private boolean supported(String claim, List<VerifiedCitation> citations) {
        Set<String> claimTokens = tokens(claim);
        Set<String> quoteTokens = new LinkedHashSet<>();
        citations.forEach(citation -> quoteTokens.addAll(tokens(citation.quote())));
        return claimTokens.stream().anyMatch(quoteTokens::contains);
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        TOKEN_RUN.matcher(value.toLowerCase(Locale.ROOT)).results().forEach(match -> {
            String token = match.group();
            if (token.codePoints().allMatch(point -> Character.UnicodeScript.of(point)
                    == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            } else {
                result.add(token);
            }
        });
        return result;
    }

    public record Candidate(boolean answerable, List<CandidateClaim> claims) {
        public Candidate { claims = List.copyOf(Objects.requireNonNull(claims, "claims")); }
    }

    public record CandidateClaim(String text, List<CandidateCitation> citations) {
        public CandidateClaim { citations = List.copyOf(Objects.requireNonNull(citations, "citations")); }
    }

    public record CandidateCitation(String evidenceId, String quote) { }

    public record EvidenceSource(
            String evidenceId,
            UUID chunkId,
            UUID segmentId,
            int ordinal,
            long startMs,
            long endMs,
            String channel,
            String text,
            boolean selectable) {
        public EvidenceSource(
                String evidenceId, UUID chunkId, UUID segmentId, long startMs, long endMs,
                String channel, String text, boolean selectable) {
            this(evidenceId, chunkId, segmentId, 0, startMs, endMs, channel, text, selectable);
        }

        public EvidenceSource {
            if (evidenceId == null || evidenceId.isBlank() || chunkId == null || segmentId == null
                    || ordinal < 0 || startMs < 0 || endMs <= startMs || channel == null || channel.isBlank()
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("evidence source is invalid");
            }
        }
    }

    public record VerifiedClaim(String text, List<VerifiedCitation> citations) {
        public VerifiedClaim { citations = List.copyOf(citations); }
    }

    public record VerifiedCitation(
            String originalEvidenceId,
            UUID segmentId,
            long startMs,
            long endMs,
            String channel,
            String quote,
            String alignmentType) { }

    public record ValidationResult(boolean accepted, List<VerifiedClaim> claims, String failure) {
        public ValidationResult { claims = List.copyOf(claims); }

        static ValidationResult rejected(String failure) {
            return new ValidationResult(false, List.of(), failure);
        }
    }
}
