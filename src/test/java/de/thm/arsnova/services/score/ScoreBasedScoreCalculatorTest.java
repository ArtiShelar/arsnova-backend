/*
 * This file is part of ARSnova Backend.
 * Copyright (C) 2012-2018 The ARSnova Team and Contributors
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
package de.thm.arsnova.services.score;

import de.thm.arsnova.entities.TestClient;
import de.thm.arsnova.entities.migration.v2.ClientAuthentication;
import de.thm.arsnova.entities.transport.ScoreStatistics;
import de.thm.arsnova.persistance.SessionStatisticsRepository;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScoreBasedScoreCalculatorTest {

	private Score courseScore;
	private VariantScoreCalculator lp;

	private int id = 1;

	private String addQuestion(String questionVariant, int points) {
		final String questionId = "question" + (id++);
		final int piRound = 1;
		courseScore.addQuestion(questionId, questionVariant, piRound, points);
		return questionId;
	}

	private void addAnswer(String questionId, ClientAuthentication user, int points) {
		final int piRound = 1;
		courseScore.addAnswer(questionId, piRound, user.getUsername(), points);
	}

	@Before
	public void setUp() {
		this.courseScore = new Score();
		SessionStatisticsRepository db = mock(SessionStatisticsRepository.class);
		when(db.getLearningProgress(null)).thenReturn(courseScore);
		this.lp = new ScoreBasedScoreCalculator(db);
	}

	@Test
	public void shouldFilterBasedOnQuestionVariant() {
		// Total of 300 Points
		String q1 = this.addQuestion("lecture", 100);
		String q2 = this.addQuestion("lecture", 100);
		String q3 = this.addQuestion("lecture", 100);
		ClientAuthentication u1 = new TestClient("user1");
		ClientAuthentication u2 = new TestClient("user2");
		ClientAuthentication u3 = new TestClient("user3");
		// Both users achieve 200 points
		this.addAnswer(q1, u1, 100);
		this.addAnswer(q1, u2, 100);
		this.addAnswer(q1, u3, 0);
		this.addAnswer(q2, u1, 0);
		this.addAnswer(q2, u2, 100);
		this.addAnswer(q2, u3, 0);
		this.addAnswer(q3, u1, 100);
		this.addAnswer(q3, u2, 100);
		this.addAnswer(q3, u3, 0);

		lp.setQuestionVariant("lecture");
		ScoreStatistics u1LectureProgress = lp.getMyProgress(null, u1);
		// (500/3) / 300 ~= 0,56.
		assertEquals(56, u1LectureProgress.getCourseProgress());
		// 200 / 300 ~= 0,67.
		assertEquals(67, u1LectureProgress.getMyProgress());
	}

	@Test
	public void shouldNotContainRoundingErrors() {
		// Total of 300 Points
		String q1 = this.addQuestion("lecture", 100);
		String q2 = this.addQuestion("lecture", 100);
		String q3 = this.addQuestion("lecture", 100);
		ClientAuthentication u1 = new TestClient("user1");
		ClientAuthentication u2 = new TestClient("user2");
		// Both users achieve 200 points
		this.addAnswer(q1, u1, 100);
		this.addAnswer(q1, u2, 100);
		this.addAnswer(q2, u1, 0);
		this.addAnswer(q2, u2, 0);
		this.addAnswer(q3, u1, 100);
		this.addAnswer(q3, u2, 100);

		lp.setQuestionVariant("lecture");
		ScoreStatistics u1LectureProgress = lp.getMyProgress(null, u1);
		// 200 / 300 = 0,67
		assertEquals(67, u1LectureProgress.getCourseProgress());
		assertEquals(67, u1LectureProgress.getMyProgress());
	}

	@Test
	public void shouldConsiderAnswersOfSamePiRound() {
		ClientAuthentication u1 = new TestClient("user1");
		ClientAuthentication u2 = new TestClient("user2");
		// question is in round 2
		courseScore.addQuestion("q1", "lecture", 2, 100);
		// 25 points in round 1, 75 points in round two for the first user
		courseScore.addAnswer("q1", 1, u1.getUsername(), 25);
		courseScore.addAnswer("q1", 2, u1.getUsername(), 75);
		// 75 points in round 1, 25 points in round two for the second user
		courseScore.addAnswer("q1", 1, u2.getUsername(), 75);
		courseScore.addAnswer("q1", 2, u2.getUsername(), 25);

		ScoreStatistics u1Progress = lp.getMyProgress(null, u1);
		ScoreStatistics u2Progress = lp.getMyProgress(null, u2);

		// only the answer for round 2 should be considered
		assertEquals(50, u1Progress.getCourseProgress());
		assertEquals(75, u1Progress.getMyProgress());
		assertEquals(50, u2Progress.getCourseProgress());
		assertEquals(25, u2Progress.getMyProgress());
	}

	@Test
	public void shouldIncludeNominatorAndDenominatorOfResultExcludingStudentCount() {
		// two questions
		String q1 = this.addQuestion("lecture", 10);
		String q2 = this.addQuestion("lecture", 10);
		// three users
		ClientAuthentication u1 = new TestClient("user1");
		ClientAuthentication u2 = new TestClient("user2");
		ClientAuthentication u3 = new TestClient("user3");
		// six answers
		this.addAnswer(q1, u1, 10);
		this.addAnswer(q2, u1, 0);
		this.addAnswer(q1, u2, 10);
		this.addAnswer(q2, u2, 0);
		this.addAnswer(q1, u3, 10);
		this.addAnswer(q2, u3, 0);

		int numerator = lp.getCourseProgress(null).getNumerator();
		int denominator = lp.getCourseProgress(null).getDenominator();

		// If the percentage is wrong, then we need to adapt this test case!
		assertEquals("Precondition failed -- The underlying calculation has changed", 50, lp.getCourseProgress(null).getCourseProgress());
		assertEquals(10, numerator);
		assertEquals(20, denominator);
	}
}
