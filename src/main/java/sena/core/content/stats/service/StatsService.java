package sena.core.content.stats.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sena.core.content.room.domain.BattleRecord;
import sena.core.content.room.repository.BattleRecordRepository;
import sena.core.content.stats.domain.MatchupSkillStat;
import sena.core.content.stats.domain.MatchupStat;
import sena.core.content.stats.dto.*;
import sena.core.content.stats.repository.MatchupCommentRepository;
import sena.core.content.stats.repository.MatchupSkillStatRepository;
import sena.core.content.stats.repository.MatchupStatRepository;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static sena.core.global.exception.ErrorCode.MATCHUP_STAT_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional
public class StatsService {

        private static final int DEFAULT_PAGE_SIZE = 20;
        private static final int DETAIL_RESULT_LIMIT = 5;
        private static final int DEFENSE_RANKING_LIMIT = 5;

        private final MatchupStatRepository statRepository;
        private final BattleRecordRepository battleRecordRepository;
        private final MatchupSkillStatRepository skillStatRepository;
        private final MatchupCommentRepository commentRepository;

        @Transactional(readOnly = true)
        public SliceResponse<DefenseComboResponse> searchDefenseCombos(String hero, int page) {
                PageRequest pageable = PageRequest.of(page, DEFAULT_PAGE_SIZE);
                List<String> heroes = parseHeroes(hero);
                Slice<DefenseComboResponse> slice = statRepository.findDefenseCombos(heroes, pageable);
                return new SliceResponse<>(slice.getContent(), slice.hasNext());
        }

        @Transactional(readOnly = true)
        public List<MatchupStatResponse> getAttackStats(String defenseCombo, String defensePet) {
                List<MatchupStat> stats = statRepository.findByDefenseComboAndDefensePetOrderByTotalGamesDesc(defenseCombo, defensePet);
                Map<Long, Long> commentCounts = getCommentCounts(stats);
                return stats.stream()
                                .map(stat -> MatchupStatResponse.from(stat, commentCounts.getOrDefault(stat.getId(), 0L)))
                                .toList();
        }

        @Transactional(readOnly = true)
        public List<MatchupDetailResponse> getMatchupDetails(Long statId) {
                MatchupStat stat = getMatchupStat(statId);

                List<BattleRecord> records = battleRecordRepository.findMatchupDetails(
                                stat.getDefenseCombo(),
                                stat.getAttackCombo(),
                                stat.getDefensePet(),
                                stat.getAttackPet(),
                                DETAIL_RESULT_LIMIT);

                return records.stream()
                                .map(MatchupDetailResponse::from)
                                .toList();
        }

        @Transactional(readOnly = true)
        public List<SkillStatResponse> getAttackSkillStats(Long statId) {
                MatchupStat stat = getMatchupStat(statId);
                return aggregateSkillStats(skillStatRepository.findAttackSkillStatsByMatchupStat(stat), true, 5);
        }

        @Transactional(readOnly = true)
        public List<SkillStatResponse> getDefenseSkillTrends(Long statId) {
                MatchupStat stat = getMatchupStat(statId);
                return aggregateSkillStats(skillStatRepository.findDefenseSkillStatsByMatchupStat(stat), false, 3);
        }

        @Transactional(readOnly = true)
        public List<DefenseRankingResponse> computeDefenseRanking() {
                Slice<DefenseComboResponse> allCombos = statRepository.findDefenseCombos(null, PageRequest.of(0, 200));
                Map<String, ComboAggregationPayload> comboMap = aggregateComboStats(allCombos.getContent());
                List<String> topHeroes = rankCoreHeroes(comboMap);
                return topHeroes.stream()
                        .map(hero -> buildRankingResponse(hero, comboMap))
                        .toList();
        }

        private Map<String, ComboAggregationPayload> aggregateComboStats(List<DefenseComboResponse> combos) {
                return combos.stream()
                        .collect(Collectors.groupingBy(
                                DefenseComboResponse::defenseCombo,
                                Collectors.reducing(
                                        ComboAggregationPayload.ZERO,
                                        c -> new ComboAggregationPayload(c.totalGames(), c.totalWins()),
                                        ComboAggregationPayload::merge
                                )
                        ));
        }

        private List<String> rankCoreHeroes(Map<String, ComboAggregationPayload> comboMap) {
                Map<String, Long> heroTotalGames = new HashMap<>();
                comboMap.forEach((combo, agg) -> {
                        for (String hero : combo.split(",")) {
                                heroTotalGames.merge(hero.trim(), agg.totalGames(), Long::sum);
                        }
                });
                return heroTotalGames.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(DEFENSE_RANKING_LIMIT)
                        .map(Map.Entry::getKey)
                        .toList();
        }

        private DefenseRankingResponse buildRankingResponse(String coreHero, Map<String, ComboAggregationPayload> comboMap) {
                List<ComboDetailResponse> details = comboMap.entrySet().stream()
                        .filter(e -> Arrays.asList(e.getKey().split(",")).contains(coreHero))
                        .sorted((a, b) -> Long.compare(b.getValue().totalGames(), a.getValue().totalGames()))
                        .map(e -> new ComboDetailResponse(e.getKey(), e.getValue().totalGames(), e.getValue().wins(), e.getValue().defenseSuccessRate()))
                        .toList();

                long totalGames = details.stream().mapToLong(ComboDetailResponse::totalGames).sum();
                long totalWins = details.stream().mapToLong(ComboDetailResponse::wins).sum();
                double winRate = totalGames > 0 ? (double) (totalGames - totalWins) / totalGames * 100.0 : 0;

                return new DefenseRankingResponse(coreHero, totalGames, totalWins, winRate, details);
        }

        private List<String> parseHeroes(String hero) {
                if (hero == null || hero.isBlank()) {
                        return null;
                }
                return Arrays.stream(hero.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        }

        private MatchupStat getMatchupStat(Long statId) {
                return statRepository.findById(statId)
                        .orElseThrow(MATCHUP_STAT_NOT_FOUND::toException);
        }

        private Map<Long, Long> getCommentCounts(List<MatchupStat> stats) {
                List<Long> statIds = stats.stream().map(MatchupStat::getId).toList();
                if (statIds.isEmpty()) {
                        return Map.of();
                }
                return commentRepository.countByMatchupStatIds(statIds).stream()
                        .collect(Collectors.toMap(
                                row -> ((Number) row[0]).longValue(),
                                row -> ((Number) row[1]).longValue()
                        ));
        }

        private List<SkillStatResponse> aggregateSkillStats(List<MatchupSkillStat> stats, boolean attack, int limit) {
                Map<String, int[]> aggregated = new HashMap<>();

                for (MatchupSkillStat stat : stats) {
                        String skillOrder = normalizeSkillOrder(attack ? stat.getAttackSkillOrder() : stat.getDefenseSkillOrder());
                        if (skillOrder == null) {
                                continue;
                        }

                        int[] record = aggregated.computeIfAbsent(skillOrder, key -> new int[2]);
                        record[0] += stat.getWins();
                        record[1] += stat.getLosses();
                }

                Comparator<SkillStatResponse> comparator = Comparator
                        .comparingInt(SkillStatResponse::totalGames)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(SkillStatResponse::winRate).reversed())
                        .thenComparing(response -> attack ? response.attackSkillOrder() : response.defenseSkillOrder());

                return aggregated.entrySet().stream()
                        .map(entry -> toSkillStatResponse(entry.getKey(), entry.getValue()[0], entry.getValue()[1], attack))
                        .sorted(comparator)
                        .limit(limit)
                        .toList();
        }

        private SkillStatResponse toSkillStatResponse(String skillOrder, int wins, int losses, boolean attack) {
                int totalGames = wins + losses;
                double winRate = totalGames > 0 ? (double) wins / totalGames * 100.0 : 0;

                return attack
                        ? new SkillStatResponse(null, skillOrder, wins, losses, totalGames, winRate)
                        : new SkillStatResponse(skillOrder, null, wins, losses, totalGames, winRate);
        }

        private String normalizeSkillOrder(String skillOrder) {
                if (skillOrder == null || skillOrder.isBlank()) {
                        return null;
                }

                String normalized = Arrays.stream(skillOrder.split(","))
                        .map(String::trim)
                        .filter(token -> !token.isEmpty())
                        .collect(Collectors.joining(","));

                return normalized.isEmpty() ? null : normalized;
        }
}
