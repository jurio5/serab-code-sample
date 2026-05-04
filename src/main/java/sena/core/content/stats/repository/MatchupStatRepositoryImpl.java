package sena.core.content.stats.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import sena.core.content.stats.domain.QMatchupStat;
import sena.core.content.stats.dto.DefenseComboResponse;

import java.util.List;

@RequiredArgsConstructor
public class MatchupStatRepositoryImpl implements MatchupStatRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QMatchupStat stat = QMatchupStat.matchupStat;

    @Override
    public Slice<DefenseComboResponse> findDefenseCombos(List<String> heroes, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder();
        if (heroes != null) {
            heroes.forEach(hero -> where.and(stat.defenseCombo.contains(hero)));
        }

        List<DefenseComboResponse> content = queryFactory
                .select(Projections.constructor(DefenseComboResponse.class,
                        stat.defenseCombo,
                        stat.defensePet,
                        stat.totalGames.sum().longValue(),
                        stat.wins.sum().longValue(),
                        stat.losses.sum().longValue(),
                        stat.wins.sum().doubleValue()
                                .divide(stat.totalGames.sum())
                                .multiply(100.0)
                                .coalesce(0.0)))
                .from(stat)
                .where(where)
                .groupBy(stat.defenseCombo, stat.defensePet)
                .orderBy(
                        stat.totalGames.sum().desc(),
                        stat.defenseCombo.asc(),
                        stat.defensePet.asc().nullsLast())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize() + 1)
                .fetch();

        boolean hasNext = content.size() > pageable.getPageSize();
        if (hasNext) {
            content = content.subList(0, pageable.getPageSize());
        }

        return new SliceImpl<>(content, pageable, hasNext);
    }
}
