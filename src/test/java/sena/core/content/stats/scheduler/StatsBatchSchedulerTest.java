package sena.core.content.stats.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sena.core.content.room.domain.BattleRecord;
import sena.core.content.room.domain.DefenderSlot;
import sena.core.content.room.enums.BattleRecordStatus;
import sena.core.content.room.enums.BattleResult;
import sena.core.content.room.repository.BattleRecordRepository;
import sena.core.content.stats.cache.ContributorRankingCache;
import sena.core.content.stats.cache.DefenseRankingCache;
import sena.core.content.stats.domain.MatchupStat;
import sena.core.content.stats.dto.RegisteredNames;
import sena.core.content.stats.repository.MatchupStatRepository;
import sena.core.content.stats.service.UnregisteredHeroLogService;
import sena.core.global.scheduler.SchedulerStatusTracker;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatsBatchSchedulerTest {

    @Mock
    BattleRecordRepository battleRecordRepository;

    @Mock
    MatchupStatRepository matchupStatRepository;

    @Mock
    SchedulerStatusTracker tracker;

    @Mock
    DefenseRankingCache defenseRankingCacheService;

    @Mock
    ContributorRankingCache contributorRankingCacheService;

    @Mock
    UnregisteredHeroLogService unregisteredHeroLogService;

    @InjectMocks
    StatsBatchScheduler scheduler;

    @Test
    @DisplayName("PENDING 기록이 정상 집계되면 ACTIVE 상태로 변경")
    void aggregateMatchupStats_success() {
        // given
        BattleRecord record = createMockRecord("A,B,C", "펫1", "X,Y,Z", "펫2", BattleResult.WIN);
        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(record));
        given(unregisteredHeroLogService.loadRegisteredNames())
                .willReturn(new RegisteredNames(Set.of(), Set.of()));

        MatchupStat stat = mock(MatchupStat.class);
        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                "A,B,C", "펫1", "X,Y,Z", "펫2"))
                .willReturn(Optional.of(stat));

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(stat).addResult(true);
        verify(record).markAsActive();
        verify(tracker).recordSuccess(eq("stats-batch"), contains("1/1"));
    }

    @Test
    @DisplayName("PENDING 기록이 없으면 조기 종료")
    void aggregateMatchupStats_noRecords() {
        // given
        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of());

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(matchupStatRepository, never()).findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                any(), any(), any(), any());
        verify(tracker).recordSuccess(eq("stats-batch"), contains("처리할 기록 없음"));
    }

    @Test
    @DisplayName("combo가 빈 값이면 SKIPPED 처리")
    void aggregateMatchupStats_skipped() {
        // given
        BattleRecord record = createMockRecord("", "펫1", "X,Y,Z", "펫2", BattleResult.WIN);
        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(record));
        given(unregisteredHeroLogService.loadRegisteredNames())
                .willReturn(new RegisteredNames(Set.of(), Set.of()));

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(record).markAsSkipped();
        verify(matchupStatRepository, never()).save(any());
        verify(tracker).recordSuccess(eq("stats-batch"), contains("0/1"));
    }

    @Test
    @DisplayName("기존 MatchupStat이 없으면 새로 생성")
    void aggregateMatchupStats_createNewStat() {
        // given
        BattleRecord record = createMockRecord("A,B,C", "펫1", "X,Y,Z", "펫2", BattleResult.LOSE);
        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(record));
        given(unregisteredHeroLogService.loadRegisteredNames())
                .willReturn(new RegisteredNames(Set.of(), Set.of()));

        MatchupStat newStat = mock(MatchupStat.class);
        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                "A,B,C", "펫1", "X,Y,Z", "펫2"))
                .willReturn(Optional.empty());
        given(matchupStatRepository.save(any(MatchupStat.class)))
                .willReturn(newStat);

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(matchupStatRepository).save(any(MatchupStat.class));
        verify(newStat).addResult(false);
        verify(record).markAsActive();
    }

    @Test
    @DisplayName("개별 기록 처리 중 예외 발생해도 나머지 계속 처리")
    void aggregateMatchupStats_partialFailure() {
        // given
        BattleRecord failRecord = mock(BattleRecord.class);
        DefenderSlot failSlot = mock(DefenderSlot.class);
        lenient().when(failRecord.getDefenseCombo()).thenReturn("A,B,C");
        lenient().when(failRecord.getAttackCombo()).thenReturn("X,Y,Z");
        lenient().when(failRecord.getAttackPetName()).thenReturn("펫2");
        lenient().when(failRecord.getDefenderSlot()).thenReturn(failSlot);
        lenient().when(failSlot.getPetName()).thenReturn("펫1");
        lenient().when(failRecord.isWin()).thenReturn(true);

        BattleRecord successRecord = createMockRecord("D,E,F", "펫3", "G,H,I", "펫4", BattleResult.LOSE);

        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(failRecord, successRecord));
        given(unregisteredHeroLogService.loadRegisteredNames())
                .willReturn(new RegisteredNames(Set.of(), Set.of()));

        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                "A,B,C", "펫1", "X,Y,Z", "펫2"))
                .willThrow(new RuntimeException("DB error"));

        MatchupStat stat = mock(MatchupStat.class);
        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                "D,E,F", "펫3", "G,H,I", "펫4"))
                .willReturn(Optional.of(stat));

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(stat).addResult(false);
        verify(successRecord).markAsActive();
        verify(tracker).recordSuccess(eq("stats-batch"), contains("1/2"));
    }

    @Test
    @DisplayName("미등록 영웅이 감지되면 메시지에 포함")
    void aggregateMatchupStats_detectsUnregisteredHeroes() {
        // given
        BattleRecord record = createMockRecord("A,B,C", "펫1", "X,Y,Z", "펫2", BattleResult.WIN);

        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(record));

        RegisteredNames names = new RegisteredNames(Set.of(), Set.of());
        given(unregisteredHeroLogService.loadRegisteredNames()).willReturn(names);
        given(unregisteredHeroLogService.detect(record, names)).willReturn(3);

        MatchupStat stat = mock(MatchupStat.class);
        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                any(), any(), any(), any()))
                .willReturn(Optional.of(stat));

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(unregisteredHeroLogService).detect(record, names);
        verify(record).markAsActive();
        verify(tracker).recordSuccess(eq("stats-batch"), contains("미등록"));
    }

    @Test
    @DisplayName("모두 등록된 영웅/펫이면 미등록 메시지 없음")
    void aggregateMatchupStats_noUnregisteredLog() {
        // given
        BattleRecord record = createMockRecord("A,B,C", "펫1", "X,Y,Z", "펫2", BattleResult.WIN);

        given(battleRecordRepository.findByStatus(BattleRecordStatus.PENDING))
                .willReturn(List.of(record));

        RegisteredNames names = new RegisteredNames(Set.of(), Set.of());
        given(unregisteredHeroLogService.loadRegisteredNames()).willReturn(names);
        given(unregisteredHeroLogService.detect(record, names)).willReturn(0);

        MatchupStat stat = mock(MatchupStat.class);
        given(matchupStatRepository.findByDefenseComboAndDefensePetAndAttackComboAndAttackPet(
                any(), any(), any(), any()))
                .willReturn(Optional.of(stat));

        // when
        scheduler.aggregateMatchupStats();

        // then
        verify(tracker).recordSuccess(eq("stats-batch"), contains("1/1"));
    }

    private BattleRecord createMockRecord(String defenseCombo, String defensePet,
            String attackCombo, String attackPet, BattleResult result) {
        BattleRecord record = mock(BattleRecord.class);
        DefenderSlot slot = mock(DefenderSlot.class);

        given(record.getDefenseCombo()).willReturn(defenseCombo);
        given(record.getAttackCombo()).willReturn(attackCombo);
        given(record.getAttackPetName()).willReturn(attackPet);
        given(record.getDefenderSlot()).willReturn(slot);
        given(slot.getPetName()).willReturn(defensePet);

        if (!defenseCombo.isBlank() && !attackCombo.isBlank()) {
            given(record.isWin()).willReturn(result.isWin());
        }

        return record;
    }
}
