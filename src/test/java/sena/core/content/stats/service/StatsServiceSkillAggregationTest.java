package sena.core.content.stats.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import sena.core.content.room.repository.BattleRecordRepository;
import sena.core.content.stats.domain.MatchupSkillStat;
import sena.core.content.stats.domain.MatchupStat;
import sena.core.content.stats.dto.SkillStatResponse;
import sena.core.content.stats.repository.MatchupCommentRepository;
import sena.core.content.stats.repository.MatchupSkillStatRepository;
import sena.core.content.stats.repository.MatchupStatRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class StatsServiceSkillAggregationTest {

    @Mock
    private MatchupStatRepository statRepository;

    @Mock
    private BattleRecordRepository battleRecordRepository;

    @Mock
    private MatchupSkillStatRepository skillStatRepository;

    @Mock
    private MatchupCommentRepository commentRepository;

    @InjectMocks
    private StatsService statsService;

    @Test
    void getAttackSkillStats_AggregatesSameAttackOrder() {
        MatchupStat stat = createStat();
        MatchupSkillStat first = createSkillStat(stat, "카일1,카일2,여포1", "여포1,카일2,여포2", true);
        MatchupSkillStat second = createSkillStat(stat, "카일2,여포1,여포2", "여포1,카일2,여포2", false);
        MatchupSkillStat third = createSkillStat(stat, "카일1,카일2,여포1", "카구라2,카일2,카일1", true);

        given(statRepository.findById(1L)).willReturn(Optional.of(stat));
        given(skillStatRepository.findAttackSkillStatsByMatchupStat(stat)).willReturn(List.of(first, second, third));

        List<SkillStatResponse> result = statsService.getAttackSkillStats(1L);

        assertEquals(2, result.size());
        assertNull(result.getFirst().defenseSkillOrder());
        assertEquals("여포1,카일2,여포2", result.getFirst().attackSkillOrder());
        assertEquals(1, result.getFirst().wins());
        assertEquals(1, result.getFirst().losses());
        assertEquals(2, result.getFirst().totalGames());
        assertEquals(50.0, result.getFirst().winRate());
    }

    @Test
    void getDefenseSkillTrends_AggregatesSameDefenseOrder() {
        MatchupStat stat = createStat();
        MatchupSkillStat first = createSkillStat(stat, "카일1,카일2,여포1", "여포1,카일2,여포2", true);
        MatchupSkillStat second = createSkillStat(stat, "카일2,여포1,여포2", "여포1,카일2,여포2", false);
        MatchupSkillStat third = createSkillStat(stat, "카일1,카일2,여포1", "카구라2,카일2,카일1", true);

        given(statRepository.findById(1L)).willReturn(Optional.of(stat));
        given(skillStatRepository.findDefenseSkillStatsByMatchupStat(stat)).willReturn(List.of(first, second, third));

        List<SkillStatResponse> result = statsService.getDefenseSkillTrends(1L);

        assertEquals(2, result.size());
        assertNull(result.getFirst().attackSkillOrder());
        assertEquals("카일1,카일2,여포1", result.getFirst().defenseSkillOrder());
        assertEquals(2, result.getFirst().wins());
        assertEquals(0, result.getFirst().losses());
        assertEquals(2, result.getFirst().totalGames());
        assertEquals(100.0, result.getFirst().winRate());
    }

    private MatchupStat createStat() {
        MatchupStat stat = MatchupStat.create("DefA,DefB,DefC", null, "AtkA,AtkB,AtkC", null);
        ReflectionTestUtils.setField(stat, "id", 1L);
        return stat;
    }

    private MatchupSkillStat createSkillStat(MatchupStat stat, String defenseSkillOrder, String attackSkillOrder,
                                             boolean... results) {
        MatchupSkillStat skillStat = MatchupSkillStat.create(stat, defenseSkillOrder, attackSkillOrder);
        for (boolean result : results) {
            skillStat.addResult(result);
        }
        return skillStat;
    }
}
