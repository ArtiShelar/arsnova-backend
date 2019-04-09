/*
 * This file is part of ARSnova Backend.
 * Copyright (C) 2012-2019 The ARSnova Team and Contributors
 *
 * ARSnova Backend is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ARSnova Backend is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.thm.arsnova.model;

import com.fasterxml.jackson.annotation.JsonView;
import de.thm.arsnova.model.serialization.View;
import org.springframework.core.style.ToStringCreator;

import java.util.Collection;
import java.util.List;

public class AnswerStatistics {
	public static class RoundStatistics {
		public static class Combination {
			private List<Integer> selectedChoiceIndexes;
			private int count;

			public Combination(final List<Integer> selectedChoiceIndexes, final int count) {
				this.selectedChoiceIndexes = selectedChoiceIndexes;
				this.count = count;
			}

			@JsonView(View.Public.class)
			public List<Integer> getSelectedChoiceIndexes() {
				return selectedChoiceIndexes;
			}

			@JsonView(View.Public.class)
			public int getCount() {
				return count;
			}

			@Override
			public String toString() {
				return new ToStringCreator(this)
						.append("selectedChoiceIndexes", selectedChoiceIndexes)
						.append("count", count)
						.toString();
			}
		}

		private int round;
		private List<Integer> independentCounts;
		private Collection<Combination> combinatedCounts;
		private int abstentionCount;

		@JsonView(View.Public.class)
		public int getRound() {
			return round;
		}

		public void setRound(int round) {
			this.round = round;
		}

		@JsonView(View.Public.class)
		public List<Integer> getIndependentCounts() {
			return independentCounts;
		}

		public void setIndependentCounts(final List<Integer> independentCounts) {
			this.independentCounts = independentCounts;
		}

		@JsonView(View.Public.class)
		public Collection<Combination> getCombinatedCounts() {
			return combinatedCounts;
		}

		public void setCombinatedCounts(Collection<Combination> combinatedCounts) {
			this.combinatedCounts = combinatedCounts;
		}

		@JsonView(View.Public.class)
		public int getAbstentionCount() {
			return abstentionCount;
		}

		public void setAbstentionCount(int abstentionCount) {
			this.abstentionCount = abstentionCount;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this)
					.append("round", round)
					.append("independentCounts", independentCounts)
					.append("combinatedCounts", combinatedCounts)
					.append("abstentionCount", abstentionCount)
					.toString();
		}
	}

	public static class RoundTransition {
		private int roundA;
		private int roundB;
		private List<Integer> selectedChoiceIndexesA;
		private List<Integer> selectedChoiceIndexesB;
		private int count;

		public RoundTransition(final int roundA, final List<Integer> selectedChoiceIndexesA,
				final int roundB, final List<Integer> selectedChoiceIndexesB, final int count) {
			this.roundA = roundA;
			this.roundB = roundB;
			this.selectedChoiceIndexesA = selectedChoiceIndexesA;
			this.selectedChoiceIndexesB = selectedChoiceIndexesB;
			this.count = count;
		}

		@JsonView(View.Public.class)
		public int getRoundA() {
			return roundA;
		}

		@JsonView(View.Public.class)
		public int getRoundB() {
			return roundB;
		}

		@JsonView(View.Public.class)
		public List<Integer> getSelectedChoiceIndexesA() {
			return selectedChoiceIndexesA;
		}

		@JsonView(View.Public.class)
		public List<Integer> getSelectedChoiceIndexesB() {
			return selectedChoiceIndexesB;
		}

		@JsonView(View.Public.class)
		public int getCount() {
			return count;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this)
					.append("roundA", roundA)
					.append("selectedChoiceIndexesA", selectedChoiceIndexesA)
					.append("roundB", roundB)
					.append("selectedChoiceIndexesB", selectedChoiceIndexesB)
					.append("count", count)
					.toString();
		}
	}

	private String contentId;
	private List<RoundStatistics> roundStatistics;
	private List<RoundTransition> roundTransitions;

	@JsonView(View.Public.class)
	public String getContentId() {
		return contentId;
	}

	public void setContentId(final String contentId) {
		this.contentId = contentId;
	}

	@JsonView(View.Public.class)
	public List<RoundStatistics> getRoundStatistics() {
		return roundStatistics;
	}

	public void setRoundStatistics(List<RoundStatistics> roundStatistics) {
		this.roundStatistics = roundStatistics;
	}

	@JsonView(View.Public.class)
	public List<RoundTransition> getRoundTransitions() {
		return roundTransitions;
	}

	public void setRoundTransitions(List<RoundTransition> roundTransitions) {
		this.roundTransitions = roundTransitions;
	}

	@Override
	public String toString() {
		return new ToStringCreator(this)
				.append("contentId", contentId)
				.append("roundStatistics", roundStatistics)
				.append("roundTransitions", roundTransitions)
				.toString();
	}
}