/*
 * This file is part of ARSnova Backend.
 * Copyright (C) 2012-2015 The ARSnova Team
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
package de.thm.arsnova.services;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Timer;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import de.thm.arsnova.ImageUtils;
import de.thm.arsnova.dao.IDatabaseDao;
import de.thm.arsnova.entities.Answer;
import de.thm.arsnova.entities.InterposedQuestion;
import de.thm.arsnova.entities.InterposedReadingCount;
import de.thm.arsnova.entities.Question;
import de.thm.arsnova.entities.Session;
import de.thm.arsnova.entities.User;
import de.thm.arsnova.events.DeleteAllLectureAnswersEvent;
import de.thm.arsnova.events.DeleteAllPreparationAnswersEvent;
import de.thm.arsnova.events.DeleteAllQuestionsAnswersEvent;
import de.thm.arsnova.events.DeleteAnswerEvent;
import de.thm.arsnova.events.DeleteInterposedQuestionEvent;
import de.thm.arsnova.events.DeleteQuestionEvent;
import de.thm.arsnova.events.NewAnswerEvent;
import de.thm.arsnova.events.NewInterposedQuestionEvent;
import de.thm.arsnova.events.NewQuestionEvent;
import de.thm.arsnova.events.PiRoundDelayedStartEvent;
import de.thm.arsnova.events.PiRoundEndEvent;
import de.thm.arsnova.exceptions.BadRequestException;
import de.thm.arsnova.exceptions.ForbiddenException;
import de.thm.arsnova.exceptions.NotFoundException;
import de.thm.arsnova.exceptions.UnauthorizedException;

@Service
public class QuestionService implements IQuestionService, ApplicationEventPublisherAware {

	@Autowired
	private IDatabaseDao databaseDao;

	@Autowired
	private IUserService userService;

	@Autowired
	private ImageUtils imageUtils;

	@Value("${upload.filesize_b}")
	private int uploadFileSizeByte;

	private ApplicationEventPublisher publisher;

	public static final Logger LOGGER = LoggerFactory.getLogger(QuestionService.class);

	private HashMap<String, Timer> timerList = new HashMap<String, Timer>();

	public void setDatabaseDao(final IDatabaseDao databaseDao) {
		this.databaseDao = databaseDao;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Question> getSkillQuestions(final String sessionkey) {
		final Session session = getSession(sessionkey);
		final User user = userService.getCurrentUser();
		if (session.isCreator(user)) {
			return databaseDao.getSkillQuestionsForTeachers(session);
		} else {
			return databaseDao.getSkillQuestionsForUsers(session);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getSkillQuestionCount(final String sessionkey) {
		final Session session = databaseDao.getSessionFromKeyword(sessionkey);
		return databaseDao.getSkillQuestionCount(session);
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#question.getSessionKeyword(), 'session', 'owner')")
	public Question saveQuestion(final Question question) {
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());
		question.setSessionId(session.get_id());

		if ("freetext".equals(question.getQuestionType())) {
			question.setPiRound(0);
		} else if (question.getPiRound() < 1 || question.getPiRound() > 2) {
			question.setPiRound(1);
		}

		// convert imageurl to base64 if neccessary
		if ("grid".equals(question.getQuestionType())) {
			if (question.getImage().startsWith("http")) {
				final String base64ImageString = imageUtils.encodeImageToString(question.getImage());
				if (base64ImageString == null) {
					throw new BadRequestException();
				}
				question.setImage(base64ImageString);
			}

			// base64 adds offset to filesize, formula taken from: http://en.wikipedia.org/wiki/Base64#MIME
			final int fileSize = (int) ((question.getImage().length() - 814) / 1.37);
			if (fileSize > uploadFileSizeByte) {
				LOGGER.error("Could not save file. File is too large with " + fileSize + " Byte.");
				throw new BadRequestException();
			}
		}

		final Question result = databaseDao.saveQuestion(session, question);
		final NewQuestionEvent event = new NewQuestionEvent(this, session, result);
		this.publisher.publishEvent(event);

		return result;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public boolean saveQuestion(final InterposedQuestion question) {
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionId());
		final InterposedQuestion result = databaseDao.saveQuestion(session, question, userService.getCurrentUser());

		if (null != result) {
			final NewInterposedQuestionEvent event = new NewInterposedQuestionEvent(this, session, result);
			this.publisher.publishEvent(event);
			return true;
		}
		return false;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Question getQuestion(final String id) {
		final Question result = databaseDao.getQuestion(id);
		if (result == null) {
			return null;
		}
		if (!"freetext".equals(result.getQuestionType()) && 0 == result.getPiRound()) {
			/* needed for legacy questions whose piRound property has not been set */
			result.setPiRound(1);
		}

		return result;
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#questionId, 'question', 'owner')")
	public void deleteQuestion(final String questionId) {
		final Question question = databaseDao.getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}

		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());
		if (session == null) {
			throw new UnauthorizedException();
		}
		databaseDao.deleteQuestionWithAnswers(question);

		final DeleteQuestionEvent event = new DeleteQuestionEvent(this, session);
		this.publisher.publishEvent(event);
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#sessionKeyword, 'session', 'owner')")
	public void deleteAllQuestions(final String sessionKeyword) {
		final Session session = getSessionWithAuthCheck(sessionKeyword);
		databaseDao.deleteAllQuestionsWithAnswers(session);

		final DeleteQuestionEvent event = new DeleteQuestionEvent(this, session);
		this.publisher.publishEvent(event);
	}

	@Override
	public void startNewPiRound(final String questionId, User user) {		
		final Question question = databaseDao.getQuestion(questionId);
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());

		if(null == user) {
			user = userService.getCurrentUser();
		}

		cancelDelayedPiRoundChange(questionId);
		question.setActive(false);
		update(question, user);

		this.publisher.publishEvent(new PiRoundEndEvent(this, session, question));
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#questionId, 'question', 'owner')")
	public void startNewPiRoundDelayed(final String questionId, final int time) {
		final IQuestionService questionService = this;
		final User user = userService.getCurrentUser();
		final Question question = databaseDao.getQuestion(questionId);
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());

		final Date date = new Date();
		final Timer timer = new Timer();
		final Date endDate = new Date(date.getTime() + (time * 1000));
		final int round = question.getPiRound();

		if(round == 1 && question.isPiRoundFinished()) {
			question.setPiRound(round + 1);
		}

		question.setActive(true);
		question.setPiRoundActive(true);
		question.setPiRoundStartTime(date.getTime());
		question.setPiRoundEndTime(endDate.getTime());

		update(question);
		this.publisher.publishEvent(new PiRoundDelayedStartEvent(this, session, question));

		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				questionService.startNewPiRound(questionId, user);
			}
		}, endDate);
	}

	@Override
	public void cancelDelayedPiRoundChange(final String questionId) {
		Timer timer = timerList.get(questionId);

		if(null != timer) {
			timer.cancel();
			timerList.remove(questionId);
			timer.purge();
		}
	}

	private Session getSessionWithAuthCheck(final String sessionKeyword) {
		final User user = userService.getCurrentUser();
		final Session session = databaseDao.getSessionFromKeyword(sessionKeyword);
		if (user == null || session == null || !session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		return session;
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#questionId, 'interposedquestion', 'owner')")
	public void deleteInterposedQuestion(final String questionId) {
		final InterposedQuestion question = databaseDao.getInterposedQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		databaseDao.deleteInterposedQuestion(question);

		final Session session = databaseDao.getSessionFromKeyword(question.getSessionId());
		final DeleteInterposedQuestionEvent event = new DeleteInterposedQuestionEvent(this, session, question);
		this.publisher.publishEvent(event);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deleteAllInterposedQuestions(final String sessionKeyword) {
		final Session session = databaseDao.getSessionFromKeyword(sessionKeyword);
		if (session == null) {
			throw new UnauthorizedException();
		}
		final User user = getCurrentUser();
		if (session.isCreator(user)) {
			databaseDao.deleteAllInterposedQuestions(session);
		} else {
			databaseDao.deleteAllInterposedQuestions(session, user);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#questionId, 'question', 'owner')")
	public void deleteAnswers(final String questionId) {
		final Question question = databaseDao.getQuestion(questionId);
		databaseDao.deleteAnswers(question);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<String> getUnAnsweredQuestionIds(final String sessionKey) {
		final User user = getCurrentUser();
		final Session session = getSession(sessionKey);
		return databaseDao.getUnAnsweredQuestionIds(session, user);
	}

	private User getCurrentUser() {
		final User user = userService.getCurrentUser();
		if (user == null) {
			throw new UnauthorizedException();
		}
		return user;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Answer getMyAnswer(final String questionId) {
		final Question question = getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		return databaseDao.getMyAnswer(userService.getCurrentUser(), questionId, question.getPiRound());
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Answer> getAnswers(final String questionId, final int piRound) {
		final Question question = databaseDao.getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		return "freetext".equals(question.getQuestionType())
				? getFreetextAnswers(questionId)
						: databaseDao.getAnswers(question, piRound);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Answer> getAnswers(final String questionId) {
		final Question question = getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		if ("freetext".equals(question.getQuestionType())) {
			return getFreetextAnswers(questionId);
		} else {
			return databaseDao.getAnswers(question);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getAnswerCount(final String questionId) {
		final Question question = getQuestion(questionId);
		if (question == null) {
			return 0;
		}

		return databaseDao.getAnswerCount(question, question.getPiRound());
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getAbstentionAnswerCount(final String questionId) {
		final Question question = getQuestion(questionId);
		if (question == null) {
			return 0;
		}

		return databaseDao.getAbstentionAnswerCount(questionId);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Answer> getFreetextAnswers(final String questionId) {
		final List<Answer> answers = databaseDao.getFreetextAnswers(questionId);
		if (answers == null) {
			throw new NotFoundException();
		}
		/* Remove user for privacy concerns */
		for (Answer answer : answers) {
			answer.setUser(null);
		}

		return answers;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Answer> getMyAnswers(final String sessionKey) {
		final Session session = getSession(sessionKey);
		// Load questions first because we are only interested in answers of the latest piRound.
		final List<Question> questions = databaseDao.getSkillQuestionsForUsers(session);
		final Map<String, Question> questionIdToQuestion = new HashMap<String, Question>();
		for (final Question question : questions) {
			questionIdToQuestion.put(question.get_id(), question);
		}

		/* filter answers by active piRound per question */
		final List<Answer> answers = databaseDao.getMyAnswers(userService.getCurrentUser(), session);
		final List<Answer> filteredAnswers = new ArrayList<Answer>();
		for (final Answer answer : answers) {
			final Question question = questionIdToQuestion.get(answer.getQuestionId());
			if (question == null) {
				// Question is not present. Most likely it has been locked by the
				// Session's creator. Locked Questions do not appear in this list.
				continue;
			}
			if (0 == answer.getPiRound() && !"freetext".equals(question.getQuestionType())) {
				answer.setPiRound(1);
			}

			// discard all answers that aren't in the same piRound as the question
			if (answer.getPiRound() == question.getPiRound()) {
				filteredAnswers.add(answer);
			}
		}

		return filteredAnswers;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getTotalAnswerCount(final String sessionKey) {
		return databaseDao.getTotalAnswerCount(sessionKey);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getInterposedCount(final String sessionKey) {
		return databaseDao.getInterposedCount(sessionKey);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public InterposedReadingCount getInterposedReadingCount(final String sessionKey, String username) {
		final Session session = databaseDao.getSessionFromKeyword(sessionKey);
		if (session == null) {
			throw new NotFoundException();
		}
		if (username == null) {
			return databaseDao.getInterposedReadingCount(session);
		} else {
			User currentUser = userService.getCurrentUser();
			if (!currentUser.getUsername().equals(username)) {
				throw new ForbiddenException();
			}

			return databaseDao.getInterposedReadingCount(session, currentUser);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<InterposedQuestion> getInterposedQuestions(final String sessionKey) {
		final Session session = this.getSession(sessionKey);
		final User user = getCurrentUser();
		if (session.isCreator(user)) {
			return databaseDao.getInterposedQuestions(session);
		} else {
			return databaseDao.getInterposedQuestions(session, user);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public InterposedQuestion readInterposedQuestion(final String questionId) {
		final User user = userService.getCurrentUser();
		return this.readInterposedQuestionInternal(questionId, user);
	}

	/*
	 * The "internal" suffix means it is called by internal services that have no authentication!
	 * TODO: Find a better way of doing this...
	 */
	@Override
	public InterposedQuestion readInterposedQuestionInternal(final String questionId, User user) {
		final InterposedQuestion question = databaseDao.getInterposedQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionId());
		if (!question.isCreator(user) && !session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		if (session.isCreator(user)) {
			databaseDao.markInterposedQuestionAsRead(question);
		}
		return question;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Question update(final Question question) {
		final User user = userService.getCurrentUser();
		return update(question, user);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Question update(final Question question, User user) {
		final Question oldQuestion = databaseDao.getQuestion(question.get_id());
		if (null == oldQuestion) {
			throw new NotFoundException();
		}

		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());
		if (user == null || session == null || !session.isCreator(user)) {
			throw new UnauthorizedException();
		}

		if ("freetext".equals(question.getQuestionType())) {
			question.setPiRound(0);
		} else if (question.getPiRound() < 1 || question.getPiRound() > 2) {
			question.setPiRound(oldQuestion.getPiRound() > 0 ? oldQuestion.getPiRound() : 1);
		}

		final Question result = databaseDao.updateQuestion(question);

		if (!oldQuestion.isActive() && question.isActive()) {
			final NewQuestionEvent event = new NewQuestionEvent(this, session, result);
			this.publisher.publishEvent(event);
		}

		return result;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Answer saveAnswer(final String questionId, final de.thm.arsnova.entities.transport.Answer answer) {
		final User user = getCurrentUser();
		final Question question = getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}

		Answer theAnswer = answer.generateAnswerEntity(user, question);
		if ("freetext".equals(question.getQuestionType())) {
			imageUtils.generateThumbnailImage(theAnswer);
		}

		return databaseDao.saveAnswer(theAnswer, user, question, getSession(question.getSessionKeyword()));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public Answer updateAnswer(final Answer answer) {
		final User user = userService.getCurrentUser();
		final Answer realAnswer = this.getMyAnswer(answer.getQuestionId());
		if (user == null || realAnswer == null || !user.getUsername().equals(realAnswer.getUser())) {
			throw new UnauthorizedException();
		}

		final Question question = getQuestion(answer.getQuestionId());
		if ("freetext".equals(question.getQuestionType())) {
			imageUtils.generateThumbnailImage(realAnswer);
		}
		final Answer result = databaseDao.updateAnswer(realAnswer);
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());
		this.publisher.publishEvent(new NewAnswerEvent(this, session, result, user, question));

		return result;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deleteAnswer(final String questionId, final String answerId) {
		final Question question = databaseDao.getQuestion(questionId);
		if (question == null) {
			throw new NotFoundException();
		}
		final User user = userService.getCurrentUser();
		final Session session = databaseDao.getSessionFromKeyword(question.getSessionKeyword());
		if (user == null || session == null || !session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		databaseDao.deleteAnswer(answerId);

		this.publisher.publishEvent(new DeleteAnswerEvent(this, session, question));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Question> getLectureQuestions(final String sessionkey) {
		final Session session = getSession(sessionkey);
		final User user = userService.getCurrentUser();
		if (session.isCreator(user)) {
			return databaseDao.getLectureQuestionsForTeachers(session);
		} else {
			return databaseDao.getLectureQuestionsForUsers(session);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Question> getFlashcards(final String sessionkey) {
		final Session session = getSession(sessionkey);
		final User user = userService.getCurrentUser();
		if (session.isCreator(user)) {
			return databaseDao.getFlashcardsForTeachers(session);
		} else {
			return databaseDao.getFlashcardsForUsers(session);
		}
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<Question> getPreparationQuestions(final String sessionkey) {
		final Session session = getSession(sessionkey);
		final User user = userService.getCurrentUser();
		if (session.isCreator(user)) {
			return databaseDao.getPreparationQuestionsForTeachers(session);
		} else {
			return databaseDao.getPreparationQuestionsForUsers(session);
		}
	}

	private Session getSession(final String sessionkey) {
		final Session session = databaseDao.getSessionFromKeyword(sessionkey);
		if (session == null) {
			throw new NotFoundException();
		}
		return session;
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getLectureQuestionCount(final String sessionkey) {
		return databaseDao.getLectureQuestionCount(getSession(sessionkey));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getFlashcardCount(final String sessionkey) {
		return databaseDao.getFlashcardCount(getSession(sessionkey));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int getPreparationQuestionCount(final String sessionkey) {
		return databaseDao.getPreparationQuestionCount(getSession(sessionkey));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public SimpleEntry<String, List<Integer>> getAnswerAndAbstentionCountByQuestion(final String questionid) {
		final List<Integer> countList = Arrays.asList(
			getAnswerCount(questionid),
			getAbstentionAnswerCount(questionid)
		);

		return new AbstractMap.SimpleEntry<String, List<Integer>>(questionid, countList);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int countLectureQuestionAnswers(final String sessionkey) {
		return this.countLectureQuestionAnswersInternal(sessionkey);
	}

	/*
	 * The "internal" suffix means it is called by internal services that have no authentication!
	 * TODO: Find a better way of doing this...
	 */
	@Override
	public int countLectureQuestionAnswersInternal(final String sessionkey) {
		return databaseDao.countLectureQuestionAnswers(getSession(sessionkey));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public int countPreparationQuestionAnswers(final String sessionkey) {
		return this.countPreparationQuestionAnswersInternal(sessionkey);
	}

	/*
	 * The "internal" suffix means it is called by internal services that have no authentication!
	 * TODO: Find a better way of doing this...
	 */
	@Override
	public int countPreparationQuestionAnswersInternal(final String sessionkey) {
		return databaseDao.countPreparationQuestionAnswers(getSession(sessionkey));
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deleteLectureQuestions(final String sessionkey) {
		final Session session = getSessionWithAuthCheck(sessionkey);
		databaseDao.deleteAllLectureQuestionsWithAnswers(session);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deleteFlashcards(final String sessionkey) {
		final Session session = getSessionWithAuthCheck(sessionkey);
		databaseDao.deleteAllFlashcardsWithAnswers(session);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deletePreparationQuestions(final String sessionkey) {
		final Session session = getSessionWithAuthCheck(sessionkey);
		databaseDao.deleteAllPreparationQuestionsWithAnswers(session);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<String> getUnAnsweredLectureQuestionIds(final String sessionkey) {
		final User user = getCurrentUser();
		return this.getUnAnsweredLectureQuestionIds(sessionkey, user);
	}

	@Override
	public List<String> getUnAnsweredLectureQuestionIds(final String sessionkey, final User user) {
		final Session session = getSession(sessionkey);
		return databaseDao.getUnAnsweredLectureQuestionIds(session, user);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public List<String> getUnAnsweredPreparationQuestionIds(final String sessionkey) {
		final User user = getCurrentUser();
		return this.getUnAnsweredPreparationQuestionIds(sessionkey, user);
	}

	@Override
	public List<String> getUnAnsweredPreparationQuestionIds(final String sessionkey, final User user) {
		final Session session = getSession(sessionkey);
		return databaseDao.getUnAnsweredPreparationQuestionIds(session, user);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void publishAll(final String sessionkey, final boolean publish) {
		final User user = getCurrentUser();
		final Session session = getSession(sessionkey);
		if (!session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		databaseDao.publishAllQuestions(session, publish);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void publishQuestions(final String sessionkey, final boolean publish, List<Question> questions) {
		final User user = getCurrentUser();
		final Session session = getSession(sessionkey);
		if (!session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		databaseDao.publishQuestions(session, publish, questions);
	}

	@Override
	@PreAuthorize("isAuthenticated()")
	public void deleteAllQuestionsAnswers(final String sessionkey) {
		final User user = getCurrentUser();
		final Session session = getSession(sessionkey);
		if (!session.isCreator(user)) {
			throw new UnauthorizedException();
		}
		databaseDao.deleteAllQuestionsAnswers(session);

		this.publisher.publishEvent(new DeleteAllQuestionsAnswersEvent(this, session));
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#sessionkey, 'session', 'owner')")
	public void deleteAllPreparationAnswers(String sessionkey) {
		final Session session = getSession(sessionkey);
		databaseDao.deleteAllPreparationAnswers(session);

		this.publisher.publishEvent(new DeleteAllPreparationAnswersEvent(this, session));
	}

	@Override
	@PreAuthorize("isAuthenticated() and hasPermission(#sessionkey, 'session', 'owner')")
	public void deleteAllLectureAnswers(String sessionkey) {
		final Session session = getSession(sessionkey);
		databaseDao.deleteAllLectureAnswers(session);

		this.publisher.publishEvent(new DeleteAllLectureAnswersEvent(this, session));
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	@Override
	public String getImage(String questionId, String answerId) {
		final List<Answer> answers = getAnswers(questionId);
		Answer answer = null;

		for (Answer a : answers) {
			if (answerId.equals(a.get_id())) {
				answer = a;
				break;
			}
		}

		if (answer == null) {
			throw new NotFoundException();
		}

		return answer.getAnswerImage();
	}
}
