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

package de.thm.arsnova.service.score;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Calculates score for a specific question.
 */
public class QuestionScore implements Iterable<UserScore> {

	/* FIXME: what is questionId used for? */
	private String questionId;

	private String questionVariant;

	private int piRound;

	private int maximumScore;

	private List<UserScore> userScores = new ArrayList<>();

	public QuestionScore(
			final String questionId, final String questionVariant, final int piRound, final int maximumScore) {
		this.questionId = questionId;
		this.questionVariant = questionVariant;
		this.piRound = piRound;
		this.maximumScore = maximumScore;
	}

	public int getMaximum() {
		return this.maximumScore;
	}

	@Override
	public Iterator<UserScore> iterator() {
		return this.userScores.iterator();
	}

	public boolean hasScores() {
		return !this.userScores.isEmpty();
	}

	public void add(final int piRound, final String userId, final int userscore) {
		if (this.piRound == piRound) {
			userScores.add(new UserScore(userId, userscore));
		}
	}

	public int getTotalUserScore() {
		int totalScore = 0;
		for (final UserScore score : userScores) {
			totalScore += score.getScore();
		}
		return totalScore;
	}

	public int getTotalUserScore(final String userId) {
		int totalScore = 0;
		for (final UserScore score : userScores) {
			if (score.isUser(userId)) {
				totalScore += score.getScore();
			}
		}
		return totalScore;
	}

	public int getUserCount() {
		return userScores.size();
	}

	public void collectUsers(final Set<String> users) {
		for (final UserScore score : userScores) {
			users.add(score.getUserId());
		}
	}

	public boolean isVariant(final String questionVariant) {
		return this.questionVariant.equals(questionVariant);
	}
}
