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

package de.thm.arsnova.persistence.couchdb.migrations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.ektorp.DbAccessException;
import org.ektorp.DocumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import de.thm.arsnova.config.properties.CouchDbProperties;
import de.thm.arsnova.model.Answer;
import de.thm.arsnova.model.Comment;
import de.thm.arsnova.model.Content;
import de.thm.arsnova.model.ContentGroup;
import de.thm.arsnova.model.MigrationState;
import de.thm.arsnova.model.Motd;
import de.thm.arsnova.model.Room;
import de.thm.arsnova.model.UserProfile;
import de.thm.arsnova.model.migration.FromV2Migrator;
import de.thm.arsnova.model.migration.v2.DbUser;
import de.thm.arsnova.model.migration.v2.LoggedIn;
import de.thm.arsnova.model.migration.v2.MotdList;
import de.thm.arsnova.persistence.ContentRepository;
import de.thm.arsnova.persistence.RoomRepository;
import de.thm.arsnova.persistence.UserRepository;
import de.thm.arsnova.persistence.couchdb.support.MangoCouchDbConnector;
import de.thm.arsnova.persistence.couchdb.support.PagedMangoResponse;

/**
 * Performs the data migration from version 2 to version 3.
 *
 * @author Daniel Gerhardt
 */
@Service
@ConditionalOnProperty(
		name = "migrate-from",
		prefix = CouchDbProperties.PREFIX)
public class V2ToV3Migration implements Migration {
	private static final String ID = "20170914131300";
	private static final int LIMIT = 200;
	private static final long OUTDATED_AFTER = 1000L * 3600 * 24 * 30 * 6;
	private static final String FULL_INDEX_BY_TYPE = "full-index-by-type";
	private static final String USER_INDEX = "user-index";
	private static final String LOGGEDIN_INDEX = "loggedin-index";
	private static final String SESSION_INDEX = "session-index";
	private static final String SKILLQUESTION_INDEX = "skillquestion-index";
	private static final String MOTD_INDEX = "motd-index";
	private static final String MOTDLIST_INDEX = "motdlist-index";

	private static final Logger logger = LoggerFactory.getLogger(V2ToV3Migration.class);

	private FromV2Migrator migrator;
	private MangoCouchDbConnector toConnector;
	private MangoCouchDbConnector fromConnector;
	private UserRepository userRepository;
	private RoomRepository roomRepository;
	private ContentRepository contentRepository;
	private long referenceTimestamp = System.currentTimeMillis();
	private MigrationState.Migration state;

	public V2ToV3Migration(
			final FromV2Migrator migrator,
			final MangoCouchDbConnector toConnector,
			@Qualifier("couchDbMigrationConnector") final MangoCouchDbConnector fromConnector,
			final UserRepository userRepository,
			final RoomRepository roomRepository,
			final ContentRepository contentRepository) {
		this.migrator = migrator;
		this.toConnector = toConnector;
		this.fromConnector = fromConnector;
		this.userRepository = userRepository;
		this.roomRepository = roomRepository;
		this.contentRepository = contentRepository;
	}

	public String getId() {
		return ID;
	}

	public int getStepCount() {
		return 8;
	}

	@Override
	public void migrate(final MigrationState.Migration state) {
		this.state = state;
		createV2Index();
		migrator.setIgnoreRevision(true);
		try {
			switch (state.getStep()) {
				case 0:
					migrateUsers();
					break;
				case 1:
					migrateUnregisteredUsers();
					break;
				case 2:
					migrateRooms();
					break;
				case 3:
					migrateMotds();
					break;
				case 4:
					migrateComments();
					break;
				case 5:
					migrateContents();
					break;
				case 6:
					migrateContentGroups();
					break;
				case 7:
					migrateAnswers();
					break;
				default:
					throw new IllegalStateException("Invalid migration step:" + state.getStep() + ".");
			}
		} catch (final InterruptedException e) {
			throw new DbAccessException(e);
		}
		migrator.setIgnoreRevision(false);
	}

	private void createV2Index() {
		final List<MangoCouchDbConnector.MangoQuery.Sort> fields;
		final Map<String, Object> filterSelector;
		final Map<String, Object> subFilterSelector;

		fields = new ArrayList<>();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("type", false));
		fromConnector.createJsonIndex(FULL_INDEX_BY_TYPE, fields);

		filterSelector = new HashMap<>();
		filterSelector.put("type", "userdetails");
		final Map<String, String> lockedFilter = new HashMap<>();
		subFilterSelector = new HashMap<>();
		subFilterSelector.put("$exists", false);
		filterSelector.put("locked", subFilterSelector);
		fromConnector.createPartialJsonIndex(USER_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("username", false));
		fromConnector.createPartialJsonIndex(USER_INDEX, fields, filterSelector);

		filterSelector.clear();
		filterSelector.put("type", "logged_in");
		fromConnector.createPartialJsonIndex(LOGGEDIN_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("user", false));
		fromConnector.createPartialJsonIndex(LOGGEDIN_INDEX, fields, filterSelector);

		filterSelector.clear();
		filterSelector.put("type", "session");
		fromConnector.createPartialJsonIndex(SESSION_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("keyword", false));
		fromConnector.createPartialJsonIndex(SESSION_INDEX, fields, filterSelector);

		filterSelector.clear();
		filterSelector.put("type", "skill_question");
		fromConnector.createPartialJsonIndex(SKILLQUESTION_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("sessionId", false));
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("questionVariant", false));
		fromConnector.createPartialJsonIndex(SKILLQUESTION_INDEX, fields, filterSelector);

		filterSelector.clear();
		filterSelector.put("type", "motd");
		fromConnector.createPartialJsonIndex(MOTD_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("motdkey", false));
		fromConnector.createPartialJsonIndex(MOTD_INDEX, fields, filterSelector);

		filterSelector.clear();
		filterSelector.put("type", "motdlist");
		fromConnector.createPartialJsonIndex(MOTDLIST_INDEX, new ArrayList<>(), filterSelector);
		fields.clear();
		fields.add(new MangoCouchDbConnector.MangoQuery.Sort("username", false));
		fromConnector.createPartialJsonIndex(MOTDLIST_INDEX, fields, filterSelector);
	}

	private void waitForV2Index(final String name) throws InterruptedException {
		for (int i = 0; i < 10; i++) {
			if (fromConnector.initializeIndex(name)) {
				return;
			}
			Thread.sleep(10000 * Math.round(1.0 + 0.5 * i));
		}
	}

	private void migrateUsers() throws InterruptedException {
		waitForV2Index(USER_INDEX);
		waitForV2Index(LOGGEDIN_INDEX);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "userdetails");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(USER_INDEX);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<UserProfile> profilesV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.DbUser> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.DbUser.class);
			final List<de.thm.arsnova.model.migration.v2.DbUser> dbUsersV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (dbUsersV2.size() == 0) {
				break;
			}

			for (final DbUser userV2 : dbUsersV2) {
				final HashMap<String, Object> loggedInQueryOptions = new HashMap<>();
				loggedInQueryOptions.put("type", "logged_in");
				loggedInQueryOptions.put("user", userV2.getUsername());
				final MangoCouchDbConnector.MangoQuery loggedInQuery =
						new MangoCouchDbConnector.MangoQuery(loggedInQueryOptions);
				loggedInQuery.setIndexDocument(LOGGEDIN_INDEX);
				final List<LoggedIn> loggedInList = fromConnector.query(loggedInQuery, LoggedIn.class);
				final LoggedIn loggedIn = loggedInList.size() > 0 ? loggedInList.get(0) : null;

				final UserProfile profileV3 = migrator.migrate(userV2, loggedIn, loadMotdList(userV2.getUsername()));
				profileV3.setAcknowledgedMotds(migrateMotdIds(profileV3.getAcknowledgedMotds()));
				profilesV3.add(profileV3);
			}

			toConnector.executeBulk(profilesV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateUnregisteredUsers() throws InterruptedException {
		waitForV2Index(USER_INDEX);
		waitForV2Index(LOGGEDIN_INDEX);
		/* Load registered usernames to exclude them later */
		Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "userdetails");
		MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(USER_INDEX);
		query.setLimit(LIMIT);
		final Set<String> usernames = new HashSet<>();
		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}", skip);
			query.setSkip(skip);
			final List<String> result = fromConnector.query(query, "username", String.class);
			if (result.isEmpty()) {
				break;
			}
			usernames.addAll(result);
		}

		queryOptions = new HashMap<>();
		queryOptions.put("type", "logged_in");
		query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(LOGGEDIN_INDEX);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();
		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<UserProfile> profilesV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.LoggedIn> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.LoggedIn.class);
			final List<de.thm.arsnova.model.migration.v2.LoggedIn> loggedInsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (loggedInsV2.isEmpty()) {
				break;
			}
			for (final LoggedIn loggedInV2 : loggedInsV2) {
				if (usernames.contains(loggedInV2.getUser())) {
					continue;
				}
				/* There might be rare cases of duplicate LoggedIn records for a user so add them to the filter list */
				usernames.add(loggedInV2.getUser());
				final UserProfile profileV3 = migrator.migrate(null, loggedInV2, loadMotdList(loggedInV2.getUser()));
				profileV3.setAcknowledgedMotds(migrateMotdIds(profileV3.getAcknowledgedMotds()));
				profilesV3.add(profileV3);
			}
			toConnector.executeBulk(profilesV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateRooms() throws InterruptedException {
		waitForV2Index(SESSION_INDEX);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "session");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(SESSION_INDEX);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<Room> roomsV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Room> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Room.class);
			final List<de.thm.arsnova.model.migration.v2.Room> roomsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (roomsV2.size() == 0) {
				break;
			}

			for (final de.thm.arsnova.model.migration.v2.Room roomV2 : roomsV2) {
				final List<UserProfile> profiles = userRepository.findByLoginId(roomV2.getCreator());
				if (profiles.size() == 0) {
					logger.warn("Skipping migration of Room {}. Creator {} does not exist.",
							roomV2.getId(), roomV2.getCreator());
					continue;
				}
				roomsV3.add(migrator.migrate(roomV2, Optional.of(profiles.get(0))));
			}

			toConnector.executeBulk(roomsV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateMotds() throws InterruptedException {
		waitForV2Index(MOTD_INDEX);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "motd");
		/* Exclude outdated MotDs */
		final HashMap<String, String> subQuery = new HashMap<>();
		subQuery.put("$gt", String.valueOf(referenceTimestamp - OUTDATED_AFTER));
		queryOptions.put("enddate", subQuery);
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(MOTD_INDEX);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<Motd> motdsV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Motd> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Motd.class);
			final List<de.thm.arsnova.model.migration.v2.Motd> motdsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (motdsV2.size() == 0) {
				break;
			}

			for (final de.thm.arsnova.model.migration.v2.Motd motdV2 : motdsV2) {
				if (motdV2.getAudience().equals("session")) {
					final Room room = roomRepository.findByShortId(motdV2.getSessionkey());
					/* sessionId has not been set for some old MotDs */
					if (room == null) {
						logger.warn("Skipping migration of Motd {}. Room {} does not exist.",
								motdV2.getId(), motdV2.getSessionId());
						continue;
					}
					motdV2.setSessionId(room.getId());
				}
				motdsV3.add(migrator.migrate(motdV2));
			}

			toConnector.executeBulk(motdsV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateComments() throws InterruptedException {
		waitForV2Index(FULL_INDEX_BY_TYPE);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "interposed_question");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(FULL_INDEX_BY_TYPE);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<Comment> commentsV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Comment> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Comment.class);
			final List<de.thm.arsnova.model.migration.v2.Comment> commentsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (commentsV2.size() == 0) {
				break;
			}

			for (final de.thm.arsnova.model.migration.v2.Comment commentV2 : commentsV2) {
				try {
					final Room roomV3 = roomRepository.findOne(commentV2.getSessionId());
					List<UserProfile> profiles = Collections.EMPTY_LIST;
					if (commentV2.getCreator() != null && !commentV2.getCreator().equals("")) {
						profiles = userRepository.findByLoginId(commentV2.getCreator());
					}
					if (profiles.size() == 0) {
						/* No creator is set or creator does not exist -> fallback: creator = Room owner */
						commentV2.setCreator(null);
						final Comment commentV3 = migrator.migrate(commentV2);
						commentV3.setCreatorId(roomV3.getOwnerId());
						commentsV3.add(commentV3);
					} else {
						commentsV3.add(migrator.migrate(commentV2, profiles.get(0)));
					}
				} catch (final DocumentNotFoundException e) {
					logger.warn("Skipping migration of Comment {}. Room {} does not exist.",
							commentV2.getId(), commentV2.getSessionId());
					continue;
				}
			}

			toConnector.executeBulk(commentsV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateContents() throws InterruptedException {
		waitForV2Index(FULL_INDEX_BY_TYPE);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "skill_question");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(FULL_INDEX_BY_TYPE);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<Content> contentsV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Content> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Content.class);
			final List<de.thm.arsnova.model.migration.v2.Content> contentsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (contentsV2.size() == 0) {
				break;
			}

			for (final de.thm.arsnova.model.migration.v2.Content contentV2 : contentsV2) {
				if (roomRepository.existsById(contentV2.getSessionId())) {
					try {
						contentsV3.add(migrator.migrate(contentV2));
					} catch (final IllegalArgumentException e) {
						logger.warn("Skipping migration of Content {}.", contentV2.getId(), e);
					}
				} else {
					logger.warn("Skipping migration of Content {}. Room {} does not exist.",
							contentV2.getId(), contentV2.getSessionId());
				}
			}

			toConnector.executeBulk(contentsV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private void migrateContentGroups() throws InterruptedException {
		waitForV2Index(SKILLQUESTION_INDEX);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "skill_question");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setFields(Arrays.asList("sessionId", "questionVariant", "_id"));
		final ArrayList<MangoCouchDbConnector.MangoQuery.Sort> sort = new ArrayList<>();
		sort.add(new MangoCouchDbConnector.MangoQuery.Sort("sessionId", false));
		sort.add(new MangoCouchDbConnector.MangoQuery.Sort("questionVariant", false));
		query.setSort(sort);
		query.setIndexDocument(SKILLQUESTION_INDEX);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		final Map<String, Set<String>> groups = new HashMap<>();
		String roomId = "";
		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Content> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Content.class);
			final List<de.thm.arsnova.model.migration.v2.Content> contentsV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (contentsV2.size() == 0) {
				break;
			}
			for (final de.thm.arsnova.model.migration.v2.Content contentV2 : contentsV2) {
				if (!contentRepository.existsById(contentV2.getId())) {
					continue;
				}
				if (!contentV2.getSession().equals(roomId)) {
					createContentGroups(roomId, groups);
					roomId = contentV2.getSessionId();
				}
				final Set<String> contentIds = groups.getOrDefault(contentV2.getQuestionVariant(), new LinkedHashSet<>());
				groups.put(contentV2.getQuestionVariant(), contentIds);
				contentIds.add(contentV2.getId());
			}
			state.setState(bookmark);
		}
		createContentGroups(roomId, groups);
		state.setState(null);
	}

	private void createContentGroups(final String roomId, final Map<String, Set<String>> groups) {
		if (!groups.isEmpty()) {
			final List<ContentGroup> contentGroups = new ArrayList<>();
			for (final String name : groups.keySet()) {
				final ContentGroup group = new ContentGroup();
				group.setRoomId(roomId);
				group.setName(name);
				group.setAutoSort(true);
				group.setContentIds(groups.get(name));
				contentGroups.add(group);
			}
			toConnector.executeBulk(contentGroups);
			groups.clear();
		}
	}

	private void migrateAnswers() throws InterruptedException {
		waitForV2Index(FULL_INDEX_BY_TYPE);
		final Map<String, Object> queryOptions = new HashMap<>();
		queryOptions.put("type", "skill_question_answer");
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(FULL_INDEX_BY_TYPE);
		query.setLimit(LIMIT);
		String bookmark = (String) state.getState();

		for (int skip = 0;; skip += LIMIT) {
			logger.debug("Migration progress: {}, bookmark: {}", skip, bookmark);
			query.setBookmark(bookmark);
			final List<Answer> answersV3 = new ArrayList<>();
			final PagedMangoResponse<de.thm.arsnova.model.migration.v2.Answer> response =
					fromConnector.queryForPage(query, de.thm.arsnova.model.migration.v2.Answer.class);
			final List<de.thm.arsnova.model.migration.v2.Answer> answersV2 = response.getEntities();
			bookmark = response.getBookmark();
			if (answersV2.size() == 0) {
				break;
			}

			for (final de.thm.arsnova.model.migration.v2.Answer answerV2 : answersV2) {
				if (!roomRepository.existsById(answerV2.getSessionId())) {
					logger.warn("Skipping migration of Answer {}. Room {} does not exist.",
							answerV2.getId(), answerV2.getQuestionId());
					continue;
				}
				try {
					final Content contentV3 = contentRepository.findOne(answerV2.getQuestionId());
					answersV3.add(migrator.migrate(answerV2, contentV3));
				} catch (final DocumentNotFoundException e) {
					logger.warn("Skipping migration of Answer {}. Content {} does not exist.",
							answerV2.getId(), answerV2.getQuestionId());
					continue;
				} catch (final IndexOutOfBoundsException e) {
					logger.warn("Skipping migration of Answer {}. Data inconsistency detected.", answerV2.getId());
				}
			}

			toConnector.executeBulk(answersV3);
			state.setState(bookmark);
		}
		state.setState(null);
	}

	private HashSet<String> migrateMotdIds(final Set<String> oldIds) throws InterruptedException {
		if (oldIds.isEmpty()) {
			return new HashSet<>();
		}
		waitForV2Index(MOTD_INDEX);
		final Map<String, Object> queryOptions = new HashMap<>();
		final Map<String, Set<String>> subQuery1 = new HashMap<>();
		subQuery1.put("$in", oldIds);
		queryOptions.put("type", "motd");
		queryOptions.put("motdkey", subQuery1);
		/* Exclude outdated MotDs */
		final HashMap<String, String> subQuery2 = new HashMap<>();
		subQuery2.put("$gt", String.valueOf(referenceTimestamp - OUTDATED_AFTER));
		queryOptions.put("enddate", subQuery2);
		final MangoCouchDbConnector.MangoQuery query = new MangoCouchDbConnector.MangoQuery(queryOptions);
		query.setIndexDocument(MOTD_INDEX);
		query.setLimit(LIMIT);

		return new HashSet<>(fromConnector.query(query, "_id", String.class));
	}

	private MotdList loadMotdList(final String username) throws InterruptedException {
		waitForV2Index(MOTDLIST_INDEX);
		final HashMap<String, Object> motdListQueryOptions = new HashMap<>();
		motdListQueryOptions.put("type", "motdlist");
		motdListQueryOptions.put("username", username);
		final MangoCouchDbConnector.MangoQuery motdListQuery = new MangoCouchDbConnector.MangoQuery(motdListQueryOptions);
		motdListQuery.setIndexDocument(MOTDLIST_INDEX);
		final List<MotdList> motdListList = fromConnector.query(motdListQuery, MotdList.class);

		return motdListList.size() > 0 ? motdListList.get(0) : null;
	}
}
