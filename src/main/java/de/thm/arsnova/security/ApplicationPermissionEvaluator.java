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

package de.thm.arsnova.security;

import java.io.Serializable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.intercept.RunAsUserToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import de.thm.arsnova.config.properties.SecurityProperties;
import de.thm.arsnova.model.Answer;
import de.thm.arsnova.model.Comment;
import de.thm.arsnova.model.Content;
import de.thm.arsnova.model.ContentGroup;
import de.thm.arsnova.model.Motd;
import de.thm.arsnova.model.Room;
import de.thm.arsnova.model.UserProfile;
import de.thm.arsnova.persistence.AnswerRepository;
import de.thm.arsnova.persistence.CommentRepository;
import de.thm.arsnova.persistence.ContentGroupRepository;
import de.thm.arsnova.persistence.ContentRepository;
import de.thm.arsnova.persistence.MotdRepository;
import de.thm.arsnova.persistence.RoomRepository;

/**
 * Provides access control methods that can be used in annotations.
 */
@Component
public class ApplicationPermissionEvaluator implements PermissionEvaluator {
	public static final String READ_PERMISSION = "read";
	public static final String CREATE_PERMISSION = "create";
	public static final String UPDATE_PERMISSION = "update";
	public static final String DELETE_PERMISSION = "delete";
	public static final String OWNER_PERMISSION = "owner";
	private static final Logger logger = LoggerFactory.getLogger(ApplicationPermissionEvaluator.class);

	private List<String> adminAccounts;

	@Autowired
	private RoomRepository roomRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private ContentRepository contentRepository;

	@Autowired
	ContentGroupRepository contentGroupRepository;

	@Autowired
	private AnswerRepository answerRepository;

	@Autowired
	private MotdRepository motdRepository;

	public ApplicationPermissionEvaluator(final SecurityProperties securityProperties) {
		adminAccounts = securityProperties.getAdminAccounts();
	}

	@Override
	public boolean hasPermission(
			final Authentication authentication,
			final Object targetDomainObject,
			final Object permission) {
		logger.debug("Evaluating permission: hasPermission({}, {}. {})", authentication, targetDomainObject, permission);
		if (authentication == null || targetDomainObject == null || !(permission instanceof String)) {
			return false;
		}

		final String userId = getUserId(authentication);

		return isSystemAccess(authentication) || hasAdminRole(userId)
				|| (targetDomainObject instanceof UserProfile
						&& (isAccountManagementAccess(authentication)
						|| hasUserProfilePermission(userId, ((UserProfile) targetDomainObject), permission.toString())))
				|| (targetDomainObject instanceof Room
						&& hasRoomPermission(userId, ((Room) targetDomainObject), permission.toString()))
				|| (targetDomainObject instanceof Content
						&& hasContentPermission(userId, ((Content) targetDomainObject), permission.toString()))
				|| (targetDomainObject instanceof ContentGroup
				&& hasContentGroupPermission(userId, ((ContentGroup) targetDomainObject), permission.toString()))
				|| (targetDomainObject instanceof Answer
						&& hasAnswerPermission(userId, ((Answer) targetDomainObject), permission.toString()))
				|| (targetDomainObject instanceof Comment
						&& hasCommentPermission(userId, ((Comment) targetDomainObject), permission.toString()))
				|| (targetDomainObject instanceof Motd
					&& hasMotdPermission(userId, ((Motd) targetDomainObject), permission.toString()));
	}

	@Override
	public boolean hasPermission(
			final Authentication authentication,
			final Serializable targetId,
			final String targetType,
			final Object permission) {
		logger.debug("Evaluating permission: hasPermission({}, {}, {}, {})",
				authentication, targetId, targetType, permission);
		if (authentication == null || targetId == null || targetType == null || !(permission instanceof String)) {
			return false;
		}

		final String userId = getUserId(authentication);
		if (hasAdminRole(userId)) {
			return true;
		}

		switch (targetType) {
			case "userprofile":
				final UserProfile targetUserProfile = new UserProfile();
				targetUserProfile.setId(targetId.toString());
				return isAccountManagementAccess(authentication)
						|| hasUserProfilePermission(userId, targetUserProfile, permission.toString());
			case "room":
				final Room targetRoom = roomRepository.findOne(targetId.toString());
				return targetRoom != null && hasRoomPermission(userId, targetRoom, permission.toString());
			case "content":
				final Content targetContent = contentRepository.findOne(targetId.toString());
				return targetContent != null && hasContentPermission(userId, targetContent, permission.toString());
			case "contentgroup":
				final ContentGroup targetContentGroup = contentGroupRepository.findOne(targetId.toString());
				return targetContentGroup != null
						&& hasContentGroupPermission(userId, targetContentGroup, permission.toString());
			case "answer":
				final Answer targetAnswer = answerRepository.findOne(targetId.toString());
				return targetAnswer != null && hasAnswerPermission(userId, targetAnswer, permission.toString());
			case "comment":
				final Comment targetComment = commentRepository.findOne(targetId.toString());
				return targetComment != null && hasCommentPermission(userId, targetComment, permission.toString());
			case "motd":
				final Motd targetMotd = motdRepository.findOne(targetId.toString());
				return targetMotd != null && hasMotdPermission(userId, targetMotd, permission.toString());
			default:
				return false;
		}
	}

	private boolean hasUserProfilePermission(
			final String userId,
			final UserProfile targetUserProfile,
			final String permission) {
		switch (permission) {
			case READ_PERMISSION:
				/* While the profile is readable for all authenticated users, it
				 * only contains a limited amount of properties for the default
				 * view. */
				return true;
			case CREATE_PERMISSION:
				return true;
			case OWNER_PERMISSION:
			case UPDATE_PERMISSION:
			case DELETE_PERMISSION:
				return userId.equals(targetUserProfile.getId());
			default:
				return false;
		}
	}

	private boolean hasRoomPermission(
			final String userId,
			final Room targetRoom,
			final String permission) {
		switch (permission) {
			case READ_PERMISSION:
				return !targetRoom.isClosed() || hasUserIdRoomModeratingPermission(targetRoom, userId);
			case CREATE_PERMISSION:
				return !userId.isEmpty();
			case UPDATE_PERMISSION:
				return targetRoom.getOwnerId().equals(userId)
						|| hasUserIdRoomModeratorRole(targetRoom, userId, Room.Moderator.Role.EDITING_MODERATOR);
			case OWNER_PERMISSION:
			case DELETE_PERMISSION:
				return targetRoom.getOwnerId().equals(userId);
			default:
				return false;
		}
	}

	private boolean hasContentPermission(
			final String userId,
			final Content targetContent,
			final String permission) {
		final Room room = roomRepository.findOne(targetContent.getRoomId());
		if (room == null) {
			return false;
		}

		switch (permission) {
			case READ_PERMISSION:
				return !room.isClosed() || hasUserIdRoomModeratingPermission(room, userId);
			case CREATE_PERMISSION:
			case UPDATE_PERMISSION:
			case DELETE_PERMISSION:
			case OWNER_PERMISSION:
				/* TODO: Remove owner permission for content. Use create/update/delete instead. */
				return room.getOwnerId().equals(userId)
						|| hasUserIdRoomModeratorRole(room, userId, Room.Moderator.Role.EDITING_MODERATOR);
			default:
				return false;
		}
	}

	private boolean hasContentGroupPermission(
			final String userId,
			final ContentGroup targetContentGroup,
			final String permission) {
		final Room room = roomRepository.findOne(targetContentGroup.getRoomId());
		if (room == null) {
			return false;
		}

		switch (permission) {
			case "read":
				return !room.isClosed() || hasUserIdRoomModeratingPermission(room, userId);
			case "create":
			case "update":
			case "delete":
				return room.getOwnerId().equals(userId)
						|| hasUserIdRoomModeratorRole(room, userId, Room.Moderator.Role.EDITING_MODERATOR);
			default:
				return false;
		}
	}

	private boolean hasAnswerPermission(
			final String userId,
			final Answer targetAnswer,
			final String permission) {
		final Content content = contentRepository.findOne(targetAnswer.getContentId());
		if (!hasContentPermission(userId, content, "read")) {
			return false;
		}
		final Room room;
		switch (permission) {
			case READ_PERMISSION:
				if (targetAnswer.getCreatorId().equals(userId) || content.getState().isResponsesVisible()) {
					return true;
				}
				room = roomRepository.findOne(targetAnswer.getRoomId());
				return room != null && hasUserIdRoomModeratingPermission(room, userId);
			case CREATE_PERMISSION:
				return content.getState().isResponsesEnabled();
			case OWNER_PERMISSION:
				return targetAnswer.getCreatorId().equals(userId);
			case UPDATE_PERMISSION:
				/* TODO */
				return false;
			case DELETE_PERMISSION:
				room = roomRepository.findOne(targetAnswer.getRoomId());
				return room != null && hasUserIdRoomModeratingPermission(room, userId);
			default:
				return false;
		}
	}

	private boolean hasCommentPermission(
			final String userId,
			final Comment targetComment,
			final String permission) {
		switch (permission) {
			case CREATE_PERMISSION:
				return !userId.isEmpty() && !roomRepository.findOne(targetComment.getRoomId()).isClosed();
			case OWNER_PERMISSION:
			case UPDATE_PERMISSION:
				return targetComment.getCreatorId() != null && targetComment.getCreatorId().equals(userId);
			case READ_PERMISSION:
			case DELETE_PERMISSION:
				if (targetComment.getCreatorId() != null && targetComment.getCreatorId().equals(userId)) {
					return true;
				}

				/* Allow reading & deletion by session owner */
				final Room room = roomRepository.findOne(targetComment.getRoomId());

				return room != null && hasUserIdRoomModeratingPermission(room, userId);
			default:
				return false;
		}
	}

	private boolean hasMotdPermission(
			final String userId,
			final Motd targetMotd,
			final String permission) {
		final Room room;
		switch (permission) {
			case CREATE_PERMISSION:
			case UPDATE_PERMISSION:
			case DELETE_PERMISSION:
				if (userId.isEmpty() || targetMotd.getRoomId() == null || targetMotd.getAudience() != Motd.Audience.ROOM) {
					return false;
				}
				room = roomRepository.findOne(targetMotd.getRoomId());
				if (room == null) {
					return false;
				}

				return userId.equals(room.getOwnerId())
						|| hasUserIdRoomModeratorRole(room, userId, Room.Moderator.Role.EDITING_MODERATOR);
			case READ_PERMISSION:
				if (targetMotd.getAudience() != Motd.Audience.ROOM) {
					return true;
				}
				room = roomRepository.findOne(targetMotd.getRoomId());

				return room != null && (!room.isClosed() || hasUserIdRoomModeratingPermission(room, userId));
			default:
				return false;
		}
	}

	/**
	 * Checks if the user is owner or has any moderating role for the room.
	 */
	private boolean hasUserIdRoomModeratingPermission(final Room room, final String userId) {
		return room.getOwnerId().equals(userId) || room.getModerators().stream()
				.anyMatch(m -> m.getUserId().equals(userId));
	}

	/**
	 * Checks if the user has a specific moderating role for the room.
	 *
	 * @param room The room to check the role for.
	 * @param userId The ID of the user to check the role for.
	 * @param role The role that is checked.
	 * @return Returns true if the user has the moderator role for the room.
	 */
	private boolean hasUserIdRoomModeratorRole(final Room room, final String userId, final Room.Moderator.Role role) {
		return room.getModerators().stream()
				.filter(m -> m.getUserId().equals(userId))
				.anyMatch(m -> m.getRoles().contains(role));
	}

	private boolean hasAdminRole(final String username) {
		/* TODO: only allow accounts from arsnova db */
		return adminAccounts.contains(username);
	}

	private String getUserId(final Authentication authentication) {
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken
				|| !(authentication.getPrincipal() instanceof User)) {
			return "";
		}
		final User user = (User) authentication.getPrincipal();

		return user.getId();
	}

	private boolean isSystemAccess(final Authentication auth) {
		return auth instanceof RunAsUserToken
				&& auth.getAuthorities().stream().anyMatch(ga -> ga.getAuthority().equals("ROLE_RUN_AS_SYSTEM"));
	}

	private boolean isAccountManagementAccess(final Authentication auth) {
		return auth instanceof RunAsUserToken
				&& auth.getAuthorities().stream()
					.anyMatch(ga -> ga.getAuthority().equals("ROLE_RUN_AS_ACCOUNT_MANAGEMENT"));
	}
}
