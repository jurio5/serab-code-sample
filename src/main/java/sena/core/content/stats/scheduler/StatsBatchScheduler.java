package sena.core.content.stats.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sena.core.content.room.domain.BattleRecord;
import sena.core.content.room.enums.BattleRecordStatus;
import sena.core.content.room.repository.BattleRecordRepository;
import sena.core.content.stats.cache.ContributorRankingCache;
import sena.core.content.stats.cache.DefenseRankingCache;
import sena.core.content.stats.domain.MatchupSkillStat;
import sena.core.content.stats.domain.MatchupStat;
import sena.core.content.stats.dto.RegisteredNames;
import sena.core.content.stats.repository.MatchupSkillStatRepository;
import sena.core.content.stats.repository.MatchupStatRepository;
import sena.core.content.stats.service.UnregisteredHeroLogService;
import sena.core.global.scheduler.SchedulerStatusTracker;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class StatsBatchScheduler {

    private static final String NAME = "stats-batch";

    private final BattleRecordRepository battleRecordRepository;
    private final MatchupStatRepository matchupStatRepository;
    private final MatchupSkillStatRepository matchupSkillStatRepository;
    private final SchedulerStatusTracker tracker;
    private final DefenseRankingCache defenseRankingCache;
    private final ContributorRankingCache contributorRankingCache;
    private final UnregisteredHeroLogService unregisteredHeroLogService;

    @PostConstruct
    void init() {
        tracker.register(NAME, "격파 기록 기반 매치업 통계 집계 및 랭킹 갱신", "화/목/일 03:00");
    }

    @Scheduled(cron = "0 0 3 ? * TUE,THU,SUN")
    @SchedulerLock(name = "StatsBatchScheduler.aggregateMatchupStats", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void aggregateMatchupStats() {
        tracker.recordStart(NAME);
        try {
            List<BattleRecord> pendingRecords = battleRecordRepository.findByStatus(BattleRecordStatus.PENDING);

            if (pendingRecords.isEmpty()) {
                log.debug("No pending battle records to aggregate");
                tracker.recordSuccess(NAME, "처리할 기록 없음");
                defenseRankingCache.refresh();
                contributorRankingCache.refresh();
                return;
            }

            RegisteredNames registeredNames = unregisteredHeroLogService.loadRegisteredNames();

            int processed = 0;
            int unregisteredCount = 0;

            for (BattleRecord record : pendingRecords) {
                try {
                    String defenseCombo = record.getDefenseCombo();
                    String defensePet = record.getDefenderSlot().getPetName();
                    String attackCombo = record.getAttackCombo();
                    String attackPet = record.getAttackPetName();

                    if (defenseCombo.isBlank() || attackCombo.isBlank()) {
                        record.markAsSkipped();
                        log.warn("Skipped battle record id={}: incomplete combo data", record.getId());
                        continue;
                    }

                    unregisteredCount += unregisteredHeroLogService.detect(record, registeredNames);

                    MatchupStat stat = matchupStatRepository
                            .findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                                    defenseCombo, defensePet, attackCombo, attackPet)
                            .orElseGet(() -> matchupStatRepository.save(
                                    MatchupStat.create(defenseCombo, defensePet, attackCombo, attackPet)));

                    stat.addResult(record.isWin());

                    String defenseSkillOrder = record.getDefenseSkillOrder();
                    String attackSkillOrder = record.getAttackSkillOrder();
                    if (defenseSkillOrder != null || attackSkillOrder != null) {
                        MatchupSkillStat skillStat = matchupSkillStatRepository
                                .findByMatchupStatAndDefenseSkillOrderAndAttackSkillOrder(
                                        stat, defenseSkillOrder, attackSkillOrder)
                                .orElseGet(() -> matchupSkillStatRepository.save(
                                        MatchupSkillStat.create(stat, defenseSkillOrder, attackSkillOrder)));
                        skillStat.addResult(record.isWin());
                    }
                    record.markAsActive();
                    processed++;
                } catch (Exception e) {
                    log.error("Failed to process battle record id={}: {}", record.getId(), e.getMessage());
                }
            }

            String message = processed + "/" + pendingRecords.size() + "건 처리"
                    + (unregisteredCount > 0 ? ", 미등록 " + unregisteredCount + "건 감지" : "");
            log.info("Stats batch completed: {}/{} records processed, {} unregistered detected",
                    processed, pendingRecords.size(), unregisteredCount);
            tracker.recordSuccess(NAME, message);
            defenseRankingCache.refresh();
            contributorRankingCache.refresh();
        } catch (Exception e) {
            tracker.recordFailure(NAME, e.getMessage());
            throw e;
        }
    }
}
