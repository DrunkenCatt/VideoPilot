package com.videopilot.answer.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class AnswerEvidenceGate {

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
                VerifiedCitation aligned = align(citation, sources);
                if (aligned == null) return ValidationResult.rejected("CITATION_VALIDATION_FAILED");
                citations.add(aligned);
            }
            verified.add(new VerifiedClaim(claim.text().trim(), citations));
        }
        return new ValidationResult(true, List.copyOf(verified), null);
    }

    private VerifiedCitation align(CandidateCitation citation, List<EvidenceSource> sources) {
        if (citation.evidenceId() == null || citation.evidenceId().isBlank()
                || citation.quote() == null || citation.quote().isBlank()
                || citation.quote().length() > maxQuoteCharacters) return null;
        EvidenceSource declared = sources.stream()
                .filter(EvidenceSource::selectable)
                .filter(source -> source.evidenceId().equals(citation.evidenceId()))
                .findFirst().orElse(null);
        if (declared == null) return null;
        List<EvidenceSource> neighborhood = sources.stream()
                .filter(source -> source.chunkId().equals(declared.chunkId()))
                .filter(source -> source.channel().equals(declared.channel()))
                .filter(source -> Math.abs(source.ordinal() - declared.ordinal()) <= 1)
                .toList();
        for (EvidenceSource source : neighborhood) {
            String type = alignment(citation.quote(), source.text());
            if (type != null) {
                return new VerifiedCitation(
                        citation.evidenceId(), source.segmentId(), source.startMs(), source.endMs(),
                        source.channel(), citation.quote().trim(), type);
            }
        }
        return null;
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
