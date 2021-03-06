/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graql.reasoner.state;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.cache.CacheEntry;
import grakn.core.graql.reasoner.cache.IndexedAnswerSet;
import grakn.core.graql.reasoner.cache.MultilevelSemanticCache;
import grakn.core.graql.reasoner.explanation.RuleExplanation;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.server.kb.concept.ConceptUtils;
import graql.lang.statement.Variable;

import java.util.Set;

/**
 * Query state corresponding to an atomic query (ReasonerAtomicQuery) in the resolution tree.
 */
@SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
public class AtomicState extends QueryState<ReasonerAtomicQuery> {

    private MultiUnifier cacheUnifier = null;
    private CacheEntry<ReasonerAtomicQuery, IndexedAnswerSet> cacheEntry = null;

    public AtomicState(ReasonerAtomicQuery query,
                ConceptMap sub,
                Unifier u,
                QueryStateBase parent,
                Set<ReasonerAtomicQuery> subGoals) {
        super(ReasonerQueries.atomic(query, sub),
              sub,
              u,
              parent,
              subGoals);
    }

    @Override
    ResolutionState propagateAnswer(AnswerState state) {
        ConceptMap answer = state.getAnswer();
        ReasonerAtomicQuery query = getQuery();
        if (answer.isEmpty()) return null;

        if (state.getRule() != null && query.getAtom().requiresRoleExpansion()) {
            //NB: we set the parent state as this AtomicState, otherwise we won't acknowledge expanded answers (won't cache)
            return new RoleExpansionState(answer, getUnifier(), query.getAtom().getRoleExpansionVariables(), this);
        }
        return new AnswerState(answer, getUnifier(), getParentState());
    }

    @Override
    ConceptMap consumeAnswer(AnswerState state) {
        ConceptMap answer;
        ReasonerAtomicQuery query = getQuery();
        ConceptMap baseAnswer = state.getSubstitution();
        InferenceRule rule = state.getRule();
        Unifier unifier = state.getUnifier();
        if (rule == null) {
            answer = ConceptUtils.mergeAnswers(baseAnswer, query.getSubstitution())
                    .project(query.getVarNames());
        } else {
            answer = rule.requiresMaterialisation(query.getAtom()) ?
                    materialisedAnswer(baseAnswer, rule, unifier) :
                    ruleAnswer(baseAnswer, rule, unifier);
        }
        return recordAnswer(query, answer);
    }

    /**
     * @return cache unifier if any
     */
    private MultiUnifier getCacheUnifier() {
        if (cacheUnifier == null) this.cacheUnifier = getQuery().tx().queryCache().getCacheUnifier(getQuery());
        return cacheUnifier;
    }

    private ConceptMap recordAnswer(ReasonerAtomicQuery query, ConceptMap answer) {
        if (answer.isEmpty()) return answer;
        if (cacheEntry == null) {
            cacheEntry = getQuery().tx().queryCache().record(query, answer, cacheEntry, null);
            return answer;
        }
        getQuery().tx().queryCache().record(query, answer, cacheEntry, getCacheUnifier());
        return answer;
    }

    private ConceptMap ruleAnswer(ConceptMap baseAnswer, InferenceRule rule, Unifier unifier) {
        ReasonerAtomicQuery query = getQuery();
        ConceptMap answer = unifier.apply(ConceptUtils.mergeAnswers(
                baseAnswer, rule.getHead().getRoleSubstitution())
        );
        if (answer.isEmpty()) return answer;

        return ConceptUtils.mergeAnswers(answer, query.getSubstitution())
                .project(query.getVarNames())
                .explain(new RuleExplanation(query.getPattern(), rule.getRule().id()));
    }

    private ConceptMap materialisedAnswer(ConceptMap baseAnswer, InferenceRule rule, Unifier unifier) {
        ConceptMap answer = baseAnswer;
        ReasonerAtomicQuery query = getQuery();
        MultilevelSemanticCache cache = getQuery().tx().queryCache();

        ReasonerAtomicQuery subbedQuery = ReasonerQueries.atomic(query, answer);
        ReasonerAtomicQuery ruleHead = ReasonerQueries.atomic(rule.getHead(), answer);

        Set<Variable> queryVars = query.getVarNames().size() < ruleHead.getVarNames().size() ?
                unifier.keySet() :
                ruleHead.getVarNames();

        boolean queryEquivalentToHead = subbedQuery.isEquivalent(ruleHead);

        //check if the specific answer to ruleHead already in cache/db
        ConceptMap headAnswer = unifier.apply(
                cache
                        .findAnswer(ruleHead, answer)
                        .project(queryVars)
        );

        //if not and query different than rule head do the same with the query
        ConceptMap queryAnswer = headAnswer.isEmpty() && queryEquivalentToHead ?
                cache.findAnswer(query, answer) :
                new ConceptMap();

        //ensure no duplicates created - only materialise answer if it doesn't exist in the db
        if (headAnswer.isEmpty()
                && queryAnswer.isEmpty()) {
            ConceptMap materialisedSub = ruleHead.materialise(answer).findFirst().orElse(null);
            if (materialisedSub != null) {
                if (!queryEquivalentToHead) {
                    cache.record(ruleHead, materialisedSub.explain(new RuleExplanation(query.getPattern(), rule.getRule().id())));
                }
                answer = unifier.apply(materialisedSub.project(queryVars));
            }
        } else {
            answer = headAnswer.isEmpty() ? queryAnswer : headAnswer;
        }

        if (answer.isEmpty()) return answer;

        return ConceptUtils
                .mergeAnswers(answer, query.getSubstitution())
                .explain(new RuleExplanation(query.getPattern(), rule.getRule().id()));
    }
}
