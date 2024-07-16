/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2024 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.services;

import static android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
import static android.provider.Settings.System.DEFAULT_RINGTONE_URI;
import static androidx.core.app.NotificationCompat.MessagingStyle.MAXIMUM_RETAINED_MESSAGES;
import static ch.threema.app.ThreemaApplication.WORK_SYNC_NOTIFICATION_ID;
import static ch.threema.app.backuprestore.csv.RestoreService.RESTORE_COMPLETION_NOTIFICATION_ID;
import static ch.threema.app.notifications.NotificationBuilderWrapper.VIBRATE_PATTERN_GROUP_CALL;
import static ch.threema.app.utils.IntentDataUtil.PENDING_INTENT_FLAG_IMMUTABLE;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_ACTIVITY_MODE;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CALL_ID;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CONTACT_IDENTITY;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_IS_INITIATOR;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AsyncQueryHandler;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.LocusIdCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.activities.BackupAdminActivity;
import ch.threema.app.activities.ComposeMessageActivity;
import ch.threema.app.activities.HomeActivity;
import ch.threema.app.activities.ServerMessageActivity;
import ch.threema.app.collections.Functional;
import ch.threema.app.collections.IPredicateNonNull;
import ch.threema.app.grouplinks.IncomingGroupRequestActivity;
import ch.threema.app.grouplinks.OutgoingGroupRequestActivity;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.GroupMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.notifications.ForwardSecurityNotificationManager;
import ch.threema.app.notifications.NotificationBuilderWrapper;
import ch.threema.app.receivers.CancelResendMessagesBroadcastReceiver;
import ch.threema.app.receivers.ReSendMessagesBroadcastReceiver;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DNDUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.utils.SoundUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.utils.TextUtil;
import ch.threema.app.utils.WidgetUtil;
import ch.threema.app.voip.activities.CallActivity;
import ch.threema.app.voip.activities.GroupCallActivity;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.storage.DatabaseServiceNew;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.ConversationModel;
import ch.threema.storage.models.GroupModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ServerMessageModel;
import ch.threema.storage.models.group.IncomingGroupJoinRequestModel;
import ch.threema.storage.models.group.OutgoingGroupJoinRequestModel;

public class NotificationServiceImpl implements NotificationService {
	private static final Logger logger = LoggingUtil.getThreemaLogger("NotificationServiceImpl");
	private static final long NOTIFY_AGAIN_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;
	private static final String NAME_PREPEND_SEPARATOR = ": ";

	private final @NonNull Context context;
	private final @NonNull LockAppService lockAppService;
	private final @NonNull DeadlineListService hiddenChatsListService;
	private final @NonNull PreferenceService preferenceService;
	private final @NonNull RingtoneService ringtoneService;
	private @Nullable ContactService contactService = null;
	private @Nullable GroupService groupService = null;
	private static final int MAX_TICKER_TEXT_LENGTH = 256;
	public static final int APP_RESTART_NOTIFICATION_ID = 481773;
	private static final int GC_PENDING_INTENT_BASE = 30000;

	private static final String PIN_LOCKED_NOTIFICATION_ID = "(transition to locked state)";
	private AsyncQueryHandler queryHandler;

	private final NotificationManagerCompat notificationManagerCompat;
	private final NotificationManager notificationManager;
	private final int pendingIntentFlags;

	private final LinkedList<ConversationNotification> conversationNotifications = new LinkedList<>();
	private MessageReceiver visibleConversationReceiver;

	@NonNull
	private final ForwardSecurityNotificationManager fsNotificationManager;

	public static class NotificationSchemaImpl implements NotificationSchema {
		private boolean vibrate = false;
		private int ringerMode = 0;
		private Uri soundUri = null;
		private int color = 0;

		public NotificationSchemaImpl(Context context) {
			AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			this.setRingerMode(audioManager.getRingerMode());
		}

		@Override
		public boolean vibrate() {
			return this.vibrate;
		}

		@Override
		public int getRingerMode() {
			return this.ringerMode;
		}

		@Override
		public Uri getSoundUri() {
			return this.soundUri;
		}

		@Override
		public int getColor() {
			return this.color;
		}

		public NotificationSchemaImpl setColor(int color) {
			this.color = color;
			return this;
		}

		public NotificationSchemaImpl setSoundUri(Uri soundUri) {
			this.soundUri = soundUri;
			return this;
		}

		private void setRingerMode(int ringerMode) {
			this.ringerMode = ringerMode;
		}

		public NotificationSchemaImpl setVibrate(boolean vibrate) {
			this.vibrate = vibrate;
			return this;
		}
	}

	public NotificationServiceImpl(
		@NonNull Context context,
		@NonNull LockAppService lockAppService,
		@NonNull DeadlineListService hiddenChatsListService,
		@NonNull PreferenceService preferenceService,
		@NonNull RingtoneService ringtoneService
	) {
		this.context = context;
		this.lockAppService = lockAppService;
		this.hiddenChatsListService = hiddenChatsListService;
		this.preferenceService = preferenceService;
		this.ringtoneService = ringtoneService;
		this.notificationManagerCompat = NotificationManagerCompat.from(context);
		this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		this.fsNotificationManager = new ForwardSecurityNotificationManager(context, hiddenChatsListService);

		// poor design by Google, as usual...
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			this.pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000; // FLAG_MUTABLE
		} else {
			this.pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
		}

		initContactService();
		initGroupService();

		/* create notification channels */
		createNotificationChannels();
	}

	private void initContactService() {
		ServiceManager serviceManager = ThreemaApplication.getServiceManager();
		if (serviceManager != null) {
			try {
				this.contactService = serviceManager.getContactService();
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		}
	}

	private void initGroupService() {
		ServiceManager serviceManager = ThreemaApplication.getServiceManager();
		if (serviceManager != null) {
			try {
				this.groupService = serviceManager.getGroupService();
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		}
	}

	private ContactService getContactService() {
		if (contactService == null) {
			initContactService();
		}
		return contactService;
	}

	private GroupService getGroupService() {
		if (groupService == null) {
			initGroupService();
		}
		return groupService;
	}

	@TargetApi(Build.VERSION_CODES.O)
	@Override
	public void deleteNotificationChannels() {
		if (!ConfigUtils.supportsNotificationChannels()) {
			return;
		}

		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_PASSPHRASE);
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_WEBCLIENT);
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_IN_CALL);
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ALERT);
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_NOTICE);
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_BACKUP_RESTORE_IN_PROGRESS);
		if (ConfigUtils.isWorkBuild()) {
			notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_WORK_SYNC);
		}
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_GROUP_CALL);
		notificationManager.deleteNotificationChannelGroup(NOTIFICATION_CHANNELGROUP_CHAT);
		notificationManager.deleteNotificationChannelGroup(NOTIFICATION_CHANNELGROUP_CHAT_UPDATE);
		notificationManager.deleteNotificationChannelGroup(NOTIFICATION_CHANNELGROUP_VOIP);
	}

	@TargetApi(Build.VERSION_CODES.O)
	@Override
	public void createNotificationChannels() {
		if (!ConfigUtils.supportsNotificationChannels()) {
			return;
		}

		NotificationChannel notificationChannel;

		// passphrase notification
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_PASSPHRASE,
				context.getString(R.string.passphrase_service_name),
				NotificationManager.IMPORTANCE_LOW);
		notificationChannel.setDescription(context.getString(R.string.passphrase_service_description));
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setShowBadge(false);
		notificationChannel.setSound(null, null);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
		notificationManager.createNotificationChannel(notificationChannel);

		// webclient notifications
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_WEBCLIENT,
				context.getString(R.string.webclient),
				NotificationManager.IMPORTANCE_LOW);
		notificationChannel.setDescription(context.getString(R.string.webclient_service_description));
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setShowBadge(false);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationChannel.setSound(null, null);
		notificationManager.createNotificationChannel(notificationChannel);

		// in call notifications (also used for group calls)
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_IN_CALL,
				context.getString(R.string.call_ongoing),
				NotificationManager.IMPORTANCE_LOW);
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setShowBadge(false);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationChannel.setSound(null, null);
		notificationManager.createNotificationChannel(notificationChannel);

		// alert notification
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_ALERT,
				context.getString(R.string.notification_channel_alerts),
				NotificationManager.IMPORTANCE_HIGH);
		notificationChannel.enableLights(true);
		notificationChannel.enableVibration(true);
		notificationChannel.setShowBadge(false);
		notificationChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
				SoundUtil.getAudioAttributesForUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT));
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationManager.createNotificationChannel(notificationChannel);

		// notice notification
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_NOTICE,
				context.getString(R.string.notification_channel_notices),
				NotificationManager.IMPORTANCE_LOW);
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setShowBadge(false);
		notificationChannel.setSound(null, null);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationManager.createNotificationChannel(notificationChannel);

		// backup notification
		notificationChannel = new NotificationChannel(
			NOTIFICATION_CHANNEL_BACKUP_RESTORE_IN_PROGRESS,
				context.getString(R.string.backup_or_restore_progress),
				NotificationManager.IMPORTANCE_LOW);
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setShowBadge(false);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
		notificationChannel.setSound(null, null);
		notificationManager.createNotificationChannel(notificationChannel);

		// work sync notification
		if (ConfigUtils.isWorkBuild()) {
			notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_WORK_SYNC,
				context.getString(R.string.work_data_sync),
				NotificationManager.IMPORTANCE_LOW);
			notificationChannel.setDescription(context.getString(R.string.work_data_sync_desc));
			notificationChannel.enableLights(false);
			notificationChannel.enableVibration(false);
			notificationChannel.setShowBadge(false);
			notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			notificationChannel.setSound(null, null);
			notificationManager.createNotificationChannel(notificationChannel);
		}

		// new synced contact notification
		notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_NEW_SYNCED_CONTACTS,
				context.getString(R.string.notification_channel_new_contact),
				NotificationManager.IMPORTANCE_HIGH);
		notificationChannel.setDescription(context.getString(R.string.notification_channel_new_contact_desc));
		notificationChannel.enableLights(true);
		notificationChannel.enableVibration(true);
		notificationChannel.setShowBadge(false);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
		notificationChannel.setSound(null,null);
		notificationManager.createNotificationChannel(notificationChannel);

		// TODO: reference to this channel may be removed after Sep. 2024
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_IDENTITY_SYNC);

		// group join response notification channel
		if (ConfigUtils.supportsGroupLinks()) {
			notificationChannel = new NotificationChannel(
			NOTIFICATION_CHANNEL_GROUP_JOIN_RESPONSE,
			context.getString(R.string.group_response),
			NotificationManager.IMPORTANCE_DEFAULT);
			notificationChannel.setDescription(context.getString(R.string.notification_channel_group_join_response));
			notificationChannel.enableLights(true);
			notificationChannel.enableVibration(true);
			notificationChannel.setShowBadge(false);
			notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			notificationChannel.setSound(null,null);
			notificationManager.createNotificationChannel(notificationChannel);

			// group join request notification channel
			notificationChannel = new NotificationChannel(
				NOTIFICATION_CHANNEL_GROUP_JOIN_REQUEST,
				context.getString(R.string.group_join_request),
				NotificationManager.IMPORTANCE_DEFAULT);
			notificationChannel.setDescription(context.getString(R.string.notification_channel_group_join_request));
			notificationChannel.enableLights(true);
			notificationChannel.enableVibration(true);
			notificationChannel.setShowBadge(false);
			notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			notificationChannel.setSound(null,null);
			notificationManager.createNotificationChannel(notificationChannel);
		}

		notificationChannel = new NotificationChannel(
			NotificationService.NOTIFICATION_CHANNEL_FORWARD_SECURITY,
			context.getString(R.string.forward_security_notification_channel_name),
			NotificationManager.IMPORTANCE_HIGH
		);
		notificationChannel.enableLights(true);
		notificationChannel.enableVibration(true);
		notificationChannel.setShowBadge(true);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
		notificationChannel.setSound(null, null);

		notificationManager.createNotificationChannel(notificationChannel);
	}

	@Override
	public void setVisibleReceiver(MessageReceiver receiver) {
		if(receiver != null) {
			//cancel
			this.cancel(receiver);
		}
		this.visibleConversationReceiver = receiver;
	}

	@SuppressLint("MissingPermission")
	@Override
	public void addGroupCallNotification(@NonNull GroupModel group, @NonNull ContactModel contactModel) {
		if (getGroupService() == null) {
			logger.error("Group service is null; cannot show notification");
			return;
		}

		// Treat the visibility of a group call notification the same as a group message that contains a mention.
		MessageReceiver<?> messageReceiver = groupService.createReceiver(group);
		DNDUtil dndUtil = DNDUtil.getInstance();
		if (dndUtil.isMutedChat(messageReceiver) || dndUtil.isMutedWork()) {
			return;
		}

		NotificationCompat.Action joinAction = new NotificationCompat.Action(
			R.drawable.ic_phone_locked_outline,
			context.getString(R.string.voip_gc_join_call),
			getGroupCallJoinPendingIntent(group.getId(), pendingIntentFlags)
		);

		Intent notificationIntent = new Intent(context, ComposeMessageActivity.class);
		notificationIntent.putExtra(ThreemaApplication.INTENT_DATA_GROUP, group.getId());
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
		PendingIntent openPendingIntent = createPendingIntentWithTaskStack(notificationIntent);

		String contentText = context.getString(R.string.voip_gc_notification_call_started, NameUtil.getShortName(contactModel), group.getName());
		NotificationSchema notificationSchema = new NotificationSchemaImpl(context)
			.setSoundUri(preferenceService.getGroupCallRingtone())
			.setVibrate(preferenceService.isGroupCallVibrate());

		// public version of the notification
		NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_GROUP_CALL)
			.setContentTitle(context.getString(R.string.group_call))
			.setContentText(context.getString(R.string.voip_gc_notification_new_call_public))
			.setSmallIcon(R.drawable.ic_phone_locked_outline)
			.setColor(context.getResources().getColor(R.color.md_theme_light_primary));

		// private version of the notification
		NotificationCompat.Builder builder = new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_GROUP_CALL, notificationSchema, publicBuilder)
			.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
			.setContentTitle(context.getString(R.string.group_call))
			.setContentText(contentText)
			.setContentIntent(openPendingIntent)
			.setSmallIcon(R.drawable.ic_phone_locked_outline)
			.setLargeIcon(groupService.getAvatar(group, false))
			.setLocalOnly(true)
			.setCategory(NotificationCompat.CATEGORY_SOCIAL)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setColor(ResourcesCompat.getColor(context.getResources(), R.color.md_theme_light_primary, context.getTheme()))
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(publicBuilder.build())
			.setSound(preferenceService.getGroupCallRingtone(), AudioManager.STREAM_VOICE_CALL)
			.addAction(joinAction);

		if (preferenceService.isGroupCallVibrate()) {
			builder.setVibrate(VIBRATE_PATTERN_GROUP_CALL);
		}

		String tag = "" + group.getId();
		try {
			notificationManagerCompat.notify(tag, ThreemaApplication.INCOMING_GROUP_CALL_NOTIFICATION_ID, builder.build());
		} catch (Exception e) {
			logger.error("Exception when notifying", e);
		}
	}

	@Override
	public void cancelGroupCallNotification(int groupId) {
		PendingIntent joinIntent = getGroupCallJoinPendingIntent(groupId, PendingIntent.FLAG_NO_CREATE | PENDING_INTENT_FLAG_IMMUTABLE);
		if (joinIntent != null) {
			joinIntent.cancel();
		}
		notificationManagerCompat.cancel("" + groupId, ThreemaApplication.INCOMING_GROUP_CALL_NOTIFICATION_ID);
	}

	private PendingIntent getGroupCallJoinPendingIntent(int groupId, int flags) {
		// To make sure a new PendingIntent only for this group is created, use the group id as request code.
		return PendingIntent.getActivity(
			context,
			GC_PENDING_INTENT_BASE + groupId,
			GroupCallActivity.getJoinCallIntent(context, groupId),
			flags
		);
	}

	@Override
	public void showConversationNotification(final ConversationNotification conversationNotification, boolean updateExisting) {
		logger.debug("showConversationNotifications");

		if (ConfigUtils.hasInvalidCredentials()) {
			logger.debug("Credentials are not (or no longer) valid. Suppressing notification.");
			return;
		}

		if (preferenceService != null && preferenceService.getWizardRunning()) {
			logger.debug("Wizard in progress. Notification suppressed.");
			return;
		}

		synchronized (this.conversationNotifications) {
			//check if current receiver is the receiver of the group
			if (this.visibleConversationReceiver != null &&
				conversationNotification.getGroup().getMessageReceiver().isEqual(this.visibleConversationReceiver)) {
				//ignore notification
				logger.info("No notification - chat visible");
				return;
			}

			String uniqueId = null;
			//check if notification not exist
			if (Functional.select(this.conversationNotifications, conversationNotification1 -> TestUtil.compare(conversationNotification1.getUid(), conversationNotification.getUid())) == null) {
				uniqueId = conversationNotification.getGroup().getMessageReceiver().getUniqueIdString();
				if (!DNDUtil.getInstance().isMuted(conversationNotification.getGroup().getMessageReceiver(), conversationNotification.getRawMessage())) {
					this.conversationNotifications.addFirst(conversationNotification);
				}
			} else if (updateExisting) {
				uniqueId = conversationNotification.getGroup().getMessageReceiver().getUniqueIdString();
			}

			Map<String, ConversationNotificationGroup> uniqueNotificationGroups = new HashMap<>();

			//to refactor on merge update and add
			final ConversationNotificationGroup newestGroup = conversationNotification.getGroup();

			int numberOfNotificationsForCurrentChat = 0;

			ListIterator<ConversationNotification> iterator = this.conversationNotifications.listIterator();
			while (iterator.hasNext()) {
				ConversationNotification notification = iterator.next();
				ConversationNotificationGroup group = notification.getGroup();
				uniqueNotificationGroups.put(group.getGroupUid(), group);
				boolean isMessageDeleted = conversationNotification.isMessageDeleted();

				if (group.equals(newestGroup) && !isMessageDeleted) {
					numberOfNotificationsForCurrentChat++;
				}

				if (conversationNotification.getUid().equals(notification.getUid()) && updateExisting) {
					if (isMessageDeleted) {
						iterator.remove();
					} else {
						iterator.set(conversationNotification);
					}
				}
			}

			if (this.conversationNotifications.stream().noneMatch(notification -> Objects.equals(notification.getGroup().getGroupUid(), conversationNotification.getGroup().getGroupUid()))) {
				this.conversationNotifications.add(conversationNotification);
				cancelConversationNotification(conversationNotification.getUid());
				return;
			}

			if(!TestUtil.required(conversationNotification, newestGroup)) {
				logger.info("No notification - missing data");
				return;
			}

			if (updateExisting) {
				if (!this.preferenceService.isShowMessagePreview() || hiddenChatsListService.has(uniqueId)) {
					return;
				}

				if (this.lockAppService.isLocked()) {
					return;
				}
			}

			final String latestFullName = newestGroup.getName();
			int unreadMessagesCount = this.conversationNotifications.size();
			int unreadConversationsCount = uniqueNotificationGroups.size();
			NotificationSchema notificationSchema = this.createNotificationSchema(newestGroup, conversationNotification.getRawMessage());

			if (notificationSchema == null) {
				logger.warn("No notification - no notification schema");
				return;
			}

			if (this.lockAppService.isLocked()) {
				this.showPinLockedNewMessageNotification(notificationSchema, conversationNotification.getUid(), false);
				return;
			}

			// make sure pin locked notification is canceled
			cancelPinLockedNewMessagesNotification();

			CharSequence tickerText;
			CharSequence singleMessageText;
			String summaryText = unreadConversationsCount > 1 ?
				ConfigUtils.getSafeQuantityString(context, R.plurals.new_messages_in_chats, unreadMessagesCount, unreadMessagesCount, unreadConversationsCount) :
				ConfigUtils.getSafeQuantityString(context, R.plurals.new_messages, unreadMessagesCount, unreadMessagesCount);
			String contentTitle;
			Intent notificationIntent;

			/* set avatar, intent and contentTitle */
			notificationIntent = new Intent(context, ComposeMessageActivity.class);
			newestGroup.getMessageReceiver().prepareIntent(notificationIntent);
			contentTitle = latestFullName;

			if (hiddenChatsListService.has(uniqueId)) {
				tickerText = summaryText;
				singleMessageText = summaryText;
			} else {
				if (this.preferenceService.isShowMessagePreview()) {
					tickerText = latestFullName + NAME_PREPEND_SEPARATOR + TextUtil.trim(conversationNotification.getMessage(), MAX_TICKER_TEXT_LENGTH, "...");
					singleMessageText = conversationNotification.getMessage();
				} else {
					tickerText = latestFullName + NAME_PREPEND_SEPARATOR + summaryText;
					singleMessageText = summaryText;
				}
			}

			// Create PendingIntent for notification tab
			notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
			PendingIntent openPendingIntent = createPendingIntentWithTaskStack(notificationIntent);

			/* ************ ANDROID AUTO ************* */

			int conversationId = newestGroup.getNotificationId() * 10;

			Intent replyIntent = new Intent(context, NotificationActionService.class);
			replyIntent.setAction(NotificationActionService.ACTION_REPLY);
			IntentDataUtil.addMessageReceiverToIntent(replyIntent, newestGroup.getMessageReceiver());
			PendingIntent replyPendingIntent = PendingIntent.getService(context, conversationId, replyIntent, pendingIntentFlags);

			Intent markReadIntent = new Intent(context, NotificationActionService.class);
			markReadIntent.setAction(NotificationActionService.ACTION_MARK_AS_READ);
			IntentDataUtil.addMessageReceiverToIntent(markReadIntent, newestGroup.getMessageReceiver());
			PendingIntent markReadPendingIntent = PendingIntent.getService(context, conversationId + 1, markReadIntent, pendingIntentFlags);

			Intent ackIntent = new Intent(context, NotificationActionService.class);
			ackIntent.setAction(NotificationActionService.ACTION_ACK);
			IntentDataUtil.addMessageReceiverToIntent(ackIntent, newestGroup.getMessageReceiver());
			ackIntent.putExtra(ThreemaApplication.INTENT_DATA_MESSAGE_ID, conversationNotification.getId());
			PendingIntent ackPendingIntent = PendingIntent.getService(context, conversationId + 2, ackIntent, pendingIntentFlags);

			Intent decIntent = new Intent(context, NotificationActionService.class);
			decIntent.setAction(NotificationActionService.ACTION_DEC);
			IntentDataUtil.addMessageReceiverToIntent(decIntent, newestGroup.getMessageReceiver());
			decIntent.putExtra(ThreemaApplication.INTENT_DATA_MESSAGE_ID, conversationNotification.getId());
			PendingIntent decPendingIntent = PendingIntent.getService(context, conversationId + 3, decIntent, pendingIntentFlags);

			long timestamp = System.currentTimeMillis();
			boolean onlyAlertOnce = (timestamp - newestGroup.getLastNotificationDate()) < NOTIFY_AGAIN_TIMEOUT;
			newestGroup.setLastNotificationDate(timestamp);

			final NotificationCompat.Builder builder;

			summaryText = ConfigUtils.getSafeQuantityString(
				context,
				R.plurals.new_messages,
				numberOfNotificationsForCurrentChat,
				numberOfNotificationsForCurrentChat
			);

			if (!this.preferenceService.isShowMessagePreview() || hiddenChatsListService.has(uniqueId)) {
				singleMessageText = summaryText;
				tickerText = summaryText;
			}

			// public version of the notification
			NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_CHAT)
				.setContentTitle(summaryText)
				.setContentText(context.getString(R.string.notification_hidden_text))
				.setSmallIcon(R.drawable.ic_notification_small)
				.setColor(context.getResources().getColor(R.color.md_theme_light_primary))
				.setOnlyAlertOnce(onlyAlertOnce);

			// private version
			builder = new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_CHAT, notificationSchema, publicBuilder)
				.setContentTitle(contentTitle)
				.setContentText(singleMessageText)
				.setTicker(tickerText)
				.setSmallIcon(R.drawable.ic_notification_small)
				.setLargeIcon(newestGroup.getAvatar())
				.setColor(context.getResources().getColor(R.color.md_theme_light_primary))
				.setGroup(newestGroup.getGroupUid())
				.setGroupSummary(false)
				.setOnlyAlertOnce(onlyAlertOnce)
				.setPriority(this.preferenceService.getNotificationPriority())
				.setCategory(NotificationCompat.CATEGORY_MESSAGE)
				.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);

			// Add identity to notification for system DND priority override
			builder.addPerson(conversationNotification.getSenderPerson());

			if (this.preferenceService.isShowMessagePreview() && !hiddenChatsListService.has(uniqueId)) {
				builder.setStyle(getMessagingStyle(newestGroup, getConversationNotificationsForGroup(newestGroup)));
				if (uniqueId != null) {
					builder.setShortcutId(uniqueId);
					builder.setLocusId(new LocusIdCompat(uniqueId));
				}
				addConversationNotificationActions(builder, replyPendingIntent, ackPendingIntent, markReadPendingIntent, conversationNotification, numberOfNotificationsForCurrentChat, unreadConversationsCount, uniqueId, newestGroup);
				addWearableExtender(builder, newestGroup, ackPendingIntent, decPendingIntent, replyPendingIntent, markReadPendingIntent, numberOfNotificationsForCurrentChat, uniqueId);
			}

			builder.setContentIntent(openPendingIntent);

			this.notify(newestGroup.getNotificationId(), builder, notificationSchema, NOTIFICATION_CHANNEL_CHAT);

			logger.info("Showing notification {} sound: {}",
				conversationNotification.getUid(),
				notificationSchema.getSoundUri() != null ? notificationSchema.getSoundUri().toString() : "null");

			showIconBadge(unreadMessagesCount);
		}
	}

	private int getRandomRequestCode() {
		return (int) System.nanoTime();
	}

	private NotificationCompat.MessagingStyle getMessagingStyle(ConversationNotificationGroup group, ArrayList<ConversationNotification> notifications) {
		if (getContactService() == null) {
			return null;
		}

		String chatName = group.getName();
		boolean isGroupChat = group.getMessageReceiver() instanceof GroupMessageReceiver;
		Person.Builder builder = new Person.Builder()
			.setName(context.getString(R.string.me_myself_and_i))
			.setKey(contactService.getUniqueIdString(contactService.getMe()));

		Bitmap avatar = contactService.getAvatar(contactService.getMe(), false);
		if (avatar != null) {
			IconCompat iconCompat = IconCompat.createWithBitmap(avatar);
			builder.setIcon(iconCompat);
		}
		Person me = builder.build();

		NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle(me);
		messagingStyle.setConversationTitle(isGroupChat ? chatName : null);
		messagingStyle.setGroupConversation(isGroupChat);

		List<NotificationCompat.MessagingStyle.Message> messages = new ArrayList<>();

		for (int i = 0; i < Math.min(notifications.size(), MAXIMUM_RETAINED_MESSAGES); i++) {
			ConversationNotification notification = notifications.get(i);

			CharSequence messageText = notification.getMessage();
			Date date = notification.getWhen();

			Person person = notification.getSenderPerson();

			// hack to show full name in non-group chats
			if (!isGroupChat) {
				if (person == null) {
					person = new Person.Builder()
						.setName(chatName).build();
				} else {
					person = person.toBuilder()
						.setName(chatName).build();
				}
			}

			long created = date == null ? 0 : date.getTime();

			NotificationCompat.MessagingStyle.Message message = new NotificationCompat.MessagingStyle.Message(messageText, created, person);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && notification.getThumbnailUri() != null && notification.getThumbnailMimeType() != null) {
				message.setData(notification.getThumbnailMimeType(), notification.getThumbnailUri());
			}
			messages.add(message);
		}

		Collections.reverse(messages);

		for (NotificationCompat.MessagingStyle.Message message: messages) {
			messagingStyle.addMessage(message);
		}

		return messagingStyle;
	}

	private ArrayList<ConversationNotification> getConversationNotificationsForGroup(ConversationNotificationGroup group) {
		ArrayList<ConversationNotification> notifications = new ArrayList<>();
		for (ConversationNotification notification : conversationNotifications) {
			if (notification.getGroup().getGroupUid().equals(group.getGroupUid())) {
				notifications.add(notification);
			}
		}
		return notifications;
	}

	private void addConversationNotificationActions(NotificationCompat.Builder builder, PendingIntent replyPendingIntent, PendingIntent ackPendingIntent, PendingIntent markReadPendingIntent, ConversationNotification conversationNotification, int unreadMessagesCount, int unreadGroupsCount, String uniqueId, ConversationNotificationGroup newestGroup) {
		// add action buttons
		boolean showMarkAsReadAction = false;

		if (preferenceService.isShowMessagePreview() && !hiddenChatsListService.has(uniqueId)) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				RemoteInput remoteInput = new RemoteInput.Builder(ThreemaApplication.EXTRA_VOICE_REPLY)
					.setLabel(context.getString(R.string.compose_message_and_enter))
					.build();

				NotificationCompat.Action.Builder replyActionBuilder = new NotificationCompat.Action.Builder(
					R.drawable.ic_reply_black_18dp, context.getString(R.string.wearable_reply), replyPendingIntent)
					.addRemoteInput(remoteInput)
					.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
					.setShowsUserInterface(false);

				if (Build.VERSION.SDK_INT >= 29) {
					replyActionBuilder.setAllowGeneratedReplies(!preferenceService.getDisableSmartReplies());
				}

				builder.addAction(replyActionBuilder.build());
			}
			if (newestGroup.getMessageReceiver() instanceof GroupMessageReceiver) {
				if (unreadMessagesCount == 1) {
					builder.addAction(getThumbsUpAction(ackPendingIntent));
				}
				showMarkAsReadAction = true;
			} else if (newestGroup.getMessageReceiver() instanceof ContactMessageReceiver) {
				if (conversationNotification.getMessageType().equals(MessageType.VOIP_STATUS))  {
					// Create an intent for the call action
					Intent callActivityIntent = new Intent(context, CallActivity.class);
					callActivityIntent.putExtra(EXTRA_ACTIVITY_MODE, CallActivity.MODE_OUTGOING_CALL);
					callActivityIntent.putExtra(EXTRA_CONTACT_IDENTITY, ((ContactMessageReceiver) newestGroup.getMessageReceiver()).getContact().getIdentity());
					callActivityIntent.putExtra(EXTRA_IS_INITIATOR, true);
					callActivityIntent.putExtra(EXTRA_CALL_ID, -1L);

					PendingIntent callPendingIntent = PendingIntent.getActivity(
						context,
						getRandomRequestCode(), // http://stackoverflow.com/questions/19031861/pendingintent-not-opening-activity-in-android-4-3
						callActivityIntent,
						this.pendingIntentFlags);

					builder.addAction(
						new NotificationCompat.Action.Builder(R.drawable.ic_call_white_24dp, context.getString(R.string.voip_return_call), callPendingIntent)
							.setShowsUserInterface(true)
							.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_CALL)
							.build());
				} else {
					if (unreadMessagesCount == 1) {
						builder.addAction(getThumbsUpAction(ackPendingIntent));
					}
					showMarkAsReadAction = true;
				}
			}
		}

		if (showMarkAsReadAction) {
			builder.addAction(getMarkAsReadAction(markReadPendingIntent));
		} else {
			builder.addInvisibleAction(getMarkAsReadAction(markReadPendingIntent));
		}
	}

	private void addGroupLinkActions(NotificationCompat.Builder builder, PendingIntent acceptIntent, PendingIntent rejectIntent) {
		NotificationCompat.Action.Builder acceptActionBuilder = new NotificationCompat.Action.Builder(
			R.drawable.ic_check, context.getString(R.string.accept), acceptIntent)
			.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
			.setShowsUserInterface(false);

		if (Build.VERSION.SDK_INT >= 29) {
			acceptActionBuilder.setAllowGeneratedReplies(!preferenceService.getDisableSmartReplies());
		}

		NotificationCompat.Action.Builder rejectActionBuilder = new NotificationCompat.Action.Builder(
			R.drawable.ic_close, context.getString(R.string.reject), rejectIntent)
			.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
			.setShowsUserInterface(false);

		if (Build.VERSION.SDK_INT >= 29) {
			rejectActionBuilder.setAllowGeneratedReplies(!preferenceService.getDisableSmartReplies());
		}

		NotificationCompat.Action acceptAction = acceptActionBuilder.build();
		NotificationCompat.Action rejectAction = rejectActionBuilder.build();

		builder.addAction(acceptAction);
		builder.addAction(rejectAction);
	}

	private NotificationCompat.Action getMarkAsReadAction(PendingIntent markReadPendingIntent) {
		return new NotificationCompat.Action.Builder(R.drawable.ic_mark_read_bitmap, context.getString(R.string.mark_read_short), markReadPendingIntent)
			.setShowsUserInterface(false)
			.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
			.build();
	}

	private NotificationCompat.Action getThumbsUpAction(PendingIntent ackPendingIntent) {
		return new NotificationCompat.Action.Builder(R.drawable.ic_thumb_up_white_24dp, context.getString(R.string.acknowledge), ackPendingIntent)
			.setShowsUserInterface(false)
			.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
			.build();
	}

	private void addWearableExtender(NotificationCompat.Builder builder,
	                                 ConversationNotificationGroup newestGroup,
	                                 PendingIntent ackPendingIntent,
	                                 PendingIntent decPendingIntent,
	                                 PendingIntent replyPendingIntent,
	                                 PendingIntent markReadPendingIntent,
	                                 int numberOfUnreadMessagesForThisChat,
	                                 String uniqueId) {

		String replyLabel = String.format(context.getString(R.string.wearable_reply_label), newestGroup.getName());
		RemoteInput remoteInput = new RemoteInput.Builder(ThreemaApplication.EXTRA_VOICE_REPLY)
				.setLabel(replyLabel)
				.setChoices(context.getResources().getStringArray(R.array.wearable_reply_choices))
				.build();

		NotificationCompat.Action.Builder replyActionBuilder =
				new NotificationCompat.Action.Builder(R.drawable.ic_wear_full_reply,
						context.getString(R.string.wearable_reply), replyPendingIntent)
						.addRemoteInput(remoteInput)
						.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
						.setShowsUserInterface(false);

		NotificationCompat.Action.WearableExtender replyActionExtender =
				new NotificationCompat.Action.WearableExtender()
						.setHintDisplayActionInline(true);

		NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender()
				.addAction(replyActionBuilder.extend(replyActionExtender).build());

		if (this.preferenceService.isShowMessagePreview() && !hiddenChatsListService.has(uniqueId)) {
			if (numberOfUnreadMessagesForThisChat == 1 && newestGroup.getMessageReceiver() instanceof ContactMessageReceiver && !hiddenChatsListService.has(uniqueId)) {
				NotificationCompat.Action ackAction =
						new NotificationCompat.Action.Builder(R.drawable.ic_wear_full_ack,
								context.getString(R.string.acknowledge), ackPendingIntent)
								.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
								.build();
				wearableExtender.addAction(ackAction);

				NotificationCompat.Action decAction =
						new NotificationCompat.Action.Builder(R.drawable.ic_wear_full_decline,
								context.getString(R.string.decline), decPendingIntent)
								.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_DOWN)
								.build();
				wearableExtender.addAction(decAction);
			}

			NotificationCompat.Action markReadAction =
				new NotificationCompat.Action.Builder(R.drawable.ic_mark_read,
					context.getString(R.string.mark_read), markReadPendingIntent)
					.setShowsUserInterface(false)
					.setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
					.build();
			wearableExtender.addAction(markReadAction);
		}
		builder.extend(wearableExtender);
		builder.extend(new NotificationCompat.CarExtender()
				.setLargeIcon(newestGroup.getAvatar())
				.setColor(context.getResources().getColor(R.color.md_theme_light_primary)));
	}

	@Override
	public void cancelConversationNotificationsOnLockApp(){
		// cancel cached notification ids if still available
		if (!conversationNotifications.isEmpty()) {
			cancelCachedConversationNotifications();
		}
		// get and cancel active conversations notifications trough notificationManager
		else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cancelAllMessageCategoryNotifications()) {
			showDefaultPinLockedNewMessageNotification();
		}
		// hack to detect active conversation Notifications by checking for active pending Intent
		else if (isConversationNotificationVisible()) {
			cancel(ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID);
			showDefaultPinLockedNewMessageNotification();
		}
	}

	@Override
	public void cancelConversationNotification(@Nullable final String... uids) {
		if (uids == null) {
			logger.warn("Unique id array must not be null! Ignoring.");
			return;
		}
		synchronized (this.conversationNotifications) {
			logger.info("Cancel {} conversation notifications", uids.length);
			for (final String uid: uids) {
				ConversationNotification conversationNotification = Functional.select(this.conversationNotifications, new IPredicateNonNull<ConversationNotification>() {
					@Override
					public boolean apply(@NonNull ConversationNotification conversationNotification1) {
						return TestUtil.compare(conversationNotification1.getUid(), uid);
					}
				});

				if (conversationNotification != null) {
					logger.info("Cancel notification {}", uid);
					cancelAndDestroyConversationNotification(conversationNotification);
				} else {
					logger.info("Notification {} not found", uid);
				}
			}

			showIconBadge(this.conversationNotifications.size());

			// no unread conversations left. make sure PIN locked notification is canceled as well
			if (this.conversationNotifications.isEmpty()) {
				cancelPinLockedNewMessagesNotification();
			}
		}

		WidgetUtil.updateWidgets(context);
	}

	private void cancelAndDestroyConversationNotification(@Nullable ConversationNotification conversationNotification) {
		if (conversationNotification == null) {
			return;
		}
		synchronized (this.conversationNotifications) {
			logger.info("Destroy notification {}", conversationNotification.getUid());
			cancel(conversationNotification.getGroup().getNotificationId());
			conversationNotification.destroy();
		}
	}

	@Override
	public void cancelAllCachedConversationNotifications() {
		this.cancel(ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID);

		synchronized (this.conversationNotifications) {
			if (!conversationNotifications.isEmpty()) {
				for (ConversationNotification conversationNotification : conversationNotifications) {
					this.cancelAndDestroyConversationNotification(conversationNotification);
				}
				conversationNotifications.clear();
				showDefaultPinLockedNewMessageNotification();
			}
		}
	}

	private NotificationSchema createNotificationSchema(ConversationNotificationGroup notificationGroup, CharSequence rawMessage) {
		NotificationSchemaImpl notificationSchema = new NotificationSchemaImpl(this.context);
		MessageReceiver r = notificationGroup.getMessageReceiver();

		if(r instanceof  GroupMessageReceiver) {

			if (DNDUtil.getInstance().isMuted(r, rawMessage)) {
				return null;
			}

			notificationSchema
					.setSoundUri(this.ringtoneService.getGroupRingtone(r.getUniqueIdString()))
					.setColor(getColorValue(this.preferenceService.getGroupNotificationLight()))
					.setVibrate(this.preferenceService.isGroupVibrate());
		}
		else if(r instanceof ContactMessageReceiver) {

			if (DNDUtil.getInstance().isMuted(r, null)) {
				return null;
			}

			notificationSchema
					.setSoundUri(this.ringtoneService.getContactRingtone(r.getUniqueIdString()))
					.setColor(getColorValue(this.preferenceService.getNotificationLight()))
					.setVibrate(this.preferenceService.isVibrate());
		}
		return notificationSchema;
	}

	@Override
	public void cancel(ConversationModel conversationModel) {
		if(conversationModel != null) {
			this.cancel(conversationModel.getReceiver());
		}
	}

	@Override
	public void cancel(final MessageReceiver receiver) {
		if(receiver != null) {
			int id = receiver.getUniqueId();
			String uniqueIdString = receiver.getUniqueIdString();

			this.cancel(id, uniqueIdString);
		}
		this.cancel(ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID);
	}

	@Override
	public void cancelCachedConversationNotifications() {
		/* called when pin lock becomes active */
		synchronized (this.conversationNotifications) {
			cancelAllCachedConversationNotifications();
			showIconBadge(this.conversationNotifications.size());
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.M)
	@Override
	public boolean cancelAllMessageCategoryNotifications() {
		boolean cancelledIDs = false;
		try {
			if (notificationManager != null) {
				StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
				if (notifications.length > 0) {
					for (StatusBarNotification notification : notifications) {
						if (notification.getNotification() != null && Notification.CATEGORY_MESSAGE.equals(notification.getNotification().category)) {
							notificationManager.cancel(notification.getId());
							cancelledIDs = true;
						}
					}
				}
			}
		} catch (Exception e) {
			logger.error("Could not cancel notifications of CATEGORY_MESSAGE ", e);
		}
		return cancelledIDs;
	}

	private NotificationSchema getDefaultNotificationSchema() {
		NotificationSchemaImpl notificationSchema = new NotificationSchemaImpl(this.context);
		notificationSchema
				.setVibrate(this.preferenceService.isVibrate())
				.setColor(this.getColorValue(preferenceService.getNotificationLight()))
				.setSoundUri(this.preferenceService.getNotificationSound());

		return notificationSchema;

	}

	@Override
	public boolean isConversationNotificationVisible() {
		Intent notificationIntent = new Intent(context, ComposeMessageActivity.class);
		PendingIntent test = PendingIntent.getActivity(context, ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID, notificationIntent, PendingIntent.FLAG_NO_CREATE | PENDING_INTENT_FLAG_IMMUTABLE);
		return test != null;
	}

	private void showDefaultPinLockedNewMessageNotification(){
		logger.debug("showDefaultPinLockedNewMessageNotification");
		this.showPinLockedNewMessageNotification(new NotificationService.NotificationSchema() {
			                                         @Override
			                                         public boolean vibrate() {
				                                         return false;
			                                         }

			                                         @Override
			                                         public int getRingerMode() {
				                                         return 0;
			                                         }

			                                         @Override
			                                         public Uri getSoundUri() {
				                                         return null;
			                                         }

			                                         @Override
			                                         public int getColor() {
				                                         return 0;
			                                         }
		                                         },
			PIN_LOCKED_NOTIFICATION_ID,
			true);
	}

	@Override
	public void showPinLockedNewMessageNotification(NotificationSchema notificationSchema, String uid, boolean quiet) {
		NotificationCompat.Builder builder =
			new NotificationBuilderWrapper(this.context, quiet ? NOTIFICATION_CHANNEL_CHAT_UPDATE : NOTIFICATION_CHANNEL_CHAT, notificationSchema)
					.setSmallIcon(R.drawable.ic_notification_small)
					.setContentTitle(this.context.getString(R.string.new_messages_locked))
					.setContentText(this.context.getString(R.string.new_messages_locked_description))
					.setTicker(this.context.getString(R.string.new_messages_locked))
					.setCategory(NotificationCompat.CATEGORY_MESSAGE)
					.setPriority(this.preferenceService.getNotificationPriority())
					.setOnlyAlertOnce(false)
					.setContentIntent(getPendingIntentForActivity(HomeActivity.class));

		this.notify(ThreemaApplication.NEW_MESSAGE_PIN_LOCKED_NOTIFICATION_ID, builder, null, quiet ? NOTIFICATION_CHANNEL_CHAT_UPDATE : NOTIFICATION_CHANNEL_CHAT);

		showIconBadge(0);

		// cancel this message as soon as the app is unlocked
		this.lockAppService.addOnLockAppStateChanged(new LockAppService.OnLockAppStateChanged() {
			@Override
			public boolean changed(boolean locked) {
				logger.debug("LockAppState changed. locked = " + locked);
				if (!locked) {
					cancelPinLockedNewMessagesNotification();
					return true;
				}
				return false;
			}
		});

		logger.info("Showing generic notification (pin locked) = {} quiet (unprotected > pin) = {} ", uid, quiet);
	}

	@Override
	public void showMasterKeyLockedNewMessageNotification() {
		this.showMasterKeyLockedNewMessageNotification(this.getDefaultNotificationSchema());
	}

	private void showMasterKeyLockedNewMessageNotification(NotificationSchema notificationSchema) {
		NotificationCompat.Builder builder =
			new NotificationBuilderWrapper(this.context, NOTIFICATION_CHANNEL_CHAT, notificationSchema)
					.setSmallIcon(R.drawable.ic_notification_small)
					.setContentTitle(this.context.getString(R.string.new_messages_locked))
					.setContentText(this.context.getString(R.string.new_messages_locked_description))
					.setTicker(this.context.getString(R.string.new_messages_locked))
					.setCategory(NotificationCompat.CATEGORY_MESSAGE)
					.setOnlyAlertOnce(false)
					.setContentIntent(getPendingIntentForActivity(HomeActivity.class));

		this.notify(ThreemaApplication.NEW_MESSAGE_LOCKED_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_CHAT);

		logger.info("Showing generic notification (master key locked)");
	}

	private void cancelPinLockedNewMessagesNotification() {
		logger.debug("cancel Pin Locked New Messages");
		this.cancel(ThreemaApplication.NEW_MESSAGE_PIN_LOCKED_NOTIFICATION_ID);
	}

	@Override
	public void showServerMessage(ServerMessageModel m) {
		Intent intent = new Intent(context, ServerMessageActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PENDING_INTENT_FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		NotificationCompat.Builder builder =
			new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_NOTICE, null)
					.setSmallIcon(R.drawable.ic_error_red_24dp)
					.setTicker(this.context.getString(R.string.server_message_title))
					.setContentTitle(this.context.getString(R.string.server_message_title))
					.setContentText(this.context.getString(R.string.tap_here_for_more))
					.setContentIntent(pendingIntent)
					.setLocalOnly(true)
					.setPriority(NotificationCompat.PRIORITY_MAX)
					.setAutoCancel(true);

		this.notify(ThreemaApplication.SERVER_MESSAGE_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_NOTICE);
	}

	private PendingIntent createPendingIntentWithTaskStack(Intent intent) {
		intent.setData((Uri.parse("foobar://"+ SystemClock.elapsedRealtime())));

		TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
		stackBuilder.addNextIntentWithParentStack(intent);
		return stackBuilder.getPendingIntent(0, this.pendingIntentFlags);
	}

	private PendingIntent getPendingIntentForActivity(Class<? extends Activity> activityClass) {
		Intent notificationIntent = new Intent(this.context, activityClass);
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		return createPendingIntentWithTaskStack(notificationIntent);
	}

	@Override
	public void showUnsentMessageNotification(@NonNull List<AbstractMessageModel> failedMessages) {
		int num = failedMessages.size();

		if (num > 0) {
			Intent sendIntent = new Intent(context, ReSendMessagesBroadcastReceiver.class);
			IntentDataUtil.appendMultipleMessageTypes(failedMessages, sendIntent);

			PendingIntent sendPendingIntent = PendingIntent.getBroadcast(
					context,
					ThreemaApplication.UNSENT_MESSAGE_NOTIFICATION_ID,
					sendIntent,
					this.pendingIntentFlags);

			NotificationCompat.Action tryAgainAction =
					new NotificationCompat.Action.Builder(R.drawable.ic_wear_full_retry,
							context.getString(R.string.try_again), sendPendingIntent)
							.build();
			NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender();
			wearableExtender.addAction(tryAgainAction);

			Intent cancelIntent = new Intent(context, CancelResendMessagesBroadcastReceiver.class);
			IntentDataUtil.appendMultipleMessageTypes(failedMessages, cancelIntent);

			PendingIntent cancelSendingMessages = PendingIntent.getBroadcast(
				context,
				ThreemaApplication.UNSENT_MESSAGE_NOTIFICATION_ID,
				cancelIntent,
				this.pendingIntentFlags
			);

			String content = ConfigUtils.getSafeQuantityString(context, R.plurals.sending_message_failed, num, num);

			NotificationCompat.Builder builder =
				new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_ALERT, null)
						.setSmallIcon(R.drawable.ic_error_red_24dp)
						.setTicker(content)
						.setPriority(NotificationCompat.PRIORITY_HIGH)
						.setCategory(NotificationCompat.CATEGORY_ERROR)
						.setColor(context.getResources().getColor(R.color.material_red))
						.setContentIntent(getPendingIntentForActivity(HomeActivity.class))
						.extend(wearableExtender)
						.setContentTitle(this.context.getString(R.string.app_name))
						.setContentText(content)
						.setStyle(new NotificationCompat.BigTextStyle().bigText(content))
						.setDeleteIntent(cancelSendingMessages)
						.addAction(R.drawable.ic_refresh_white_24dp, context.getString(R.string.try_again), sendPendingIntent);

			this.notify(ThreemaApplication.UNSENT_MESSAGE_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_ALERT);
		} else {
			this.cancel(ThreemaApplication.UNSENT_MESSAGE_NOTIFICATION_ID);
		}
	}

	@Override
	public void showForwardSecurityMessageRejectedNotification(@NonNull MessageReceiver<?> messageReceiver) {
		fsNotificationManager.showForwardSecurityNotification(messageReceiver);
	}

	@Override
	public void showSafeBackupFailed(int numDays) {
		if (numDays > 0 && preferenceService.getThreemaSafeEnabled()) {
			Intent intent = new Intent(context, BackupAdminActivity.class);
			PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PENDING_INTENT_FLAG_IMMUTABLE);

			String content = String.format(this.context.getString(R.string.safe_failed_notification), numDays);

			NotificationCompat.Builder builder =
					new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_ALERT, null)
							.setSmallIcon(R.drawable.ic_error_red_24dp)
							.setTicker(content)
							.setLocalOnly(true)
							.setPriority(NotificationCompat.PRIORITY_HIGH)
							.setCategory(NotificationCompat.CATEGORY_ERROR)
							.setColor(context.getResources().getColor(R.color.material_red))
							.setContentIntent(pendingIntent)
							.setContentTitle(this.context.getString(R.string.app_name))
							.setContentText(content)
							.setStyle(new NotificationCompat.BigTextStyle().bigText(content));

			this.notify(ThreemaApplication.SAFE_FAILED_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_ALERT);
		} else {
			this.cancel(ThreemaApplication.SAFE_FAILED_NOTIFICATION_ID);
		}
	}

	@Override
	public void cancelWorkSyncProgress() {
		this.cancel(WORK_SYNC_NOTIFICATION_ID);
	}

	@Override
	public void showNewSyncedContactsNotification(List<ContactModel> contactModels) {
		if (contactModels.size() > 0) {
			String message;
			Intent notificationIntent;

			if (contactModels.size() > 1) {
				StringBuilder contactListBuilder = new StringBuilder();
				for(ContactModel contactModel: contactModels) {
					if (contactListBuilder.length() > 0) {
						contactListBuilder.append(", ");
					}
					contactListBuilder.append(NameUtil.getDisplayName(contactModel));
				}
				message = this.context.getString(R.string.notification_contact_has_joined_multiple, contactModels.size(), this.context.getString(R.string.app_name), contactListBuilder.toString());
				notificationIntent = new Intent(context, HomeActivity.class);
				notificationIntent.putExtra(HomeActivity.EXTRA_SHOW_CONTACTS, true);
			} else {
				String name = NameUtil.getDisplayName(contactModels.get(0));
				message = String.format(this.context.getString(R.string.notification_contact_has_joined), name, this.context.getString(R.string.app_name));
				notificationIntent = new Intent(context, ComposeMessageActivity.class);
				if (getContactService() != null) {
					contactService.createReceiver(contactModels.get(0)).prepareIntent(notificationIntent);
				}
			}
			notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
			PendingIntent openPendingIntent = createPendingIntentWithTaskStack(notificationIntent);

			NotificationSchemaImpl notificationSchema = new NotificationSchemaImpl(this.context);
			notificationSchema
				.setVibrate(this.preferenceService.isVibrate())
				.setColor(this.getColorValue(preferenceService.getNotificationLight()));

			NotificationCompat.Builder builder =
					new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_NEW_SYNCED_CONTACTS, notificationSchema)
							.setSmallIcon(R.drawable.ic_notification_small)
							.setContentTitle(this.context.getString(R.string.notification_channel_new_contact))
							.setContentText(message)
							.setContentIntent(openPendingIntent)
							.setStyle(new NotificationCompat.BigTextStyle().bigText(message))
							.setPriority(NotificationCompat.PRIORITY_HIGH)
							.setAutoCancel(true);

			this.notify(ThreemaApplication.NEW_SYNCED_CONTACTS_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_NEW_SYNCED_CONTACTS);
		}
	}
	/**
	 * Create and show notification
	 */
	private void notify(int id, NotificationCompat.Builder builder, @Nullable NotificationSchema schema, @NonNull String channelName) {
		try {
			notificationManagerCompat.notify(id, builder.build());
		} catch (SecurityException e) {
			// some phones revoke access to selected sound files for notifications after an OS upgrade
			logger.error("Can't show notification. Falling back to default ringtone", e);

			if (NOTIFICATION_CHANNEL_CHAT.equals(channelName) ||
				NOTIFICATION_CHANNEL_CALL.equals(channelName) ||
				NOTIFICATION_CHANNEL_CHAT_UPDATE.equals(channelName) ||
				NOTIFICATION_CHANNEL_GROUP_CALL.equals(channelName)
			) {

				if (schema != null && schema.getSoundUri() != null && !DEFAULT_NOTIFICATION_URI.equals(schema.getSoundUri()) && !DEFAULT_RINGTONE_URI.equals(schema.getSoundUri())) {
					// create a new schema with default sound
					NotificationSchemaImpl newSchema = new NotificationSchemaImpl(this.context);
					newSchema.setSoundUri(NOTIFICATION_CHANNEL_CALL.equals(channelName) || NOTIFICATION_CHANNEL_GROUP_CALL.equals(channelName) ? DEFAULT_RINGTONE_URI: DEFAULT_NOTIFICATION_URI);
					newSchema.setVibrate(schema.vibrate()).setColor(schema.getColor());
					builder.setChannelId(NotificationBuilderWrapper.init(context, channelName, newSchema, false));
					try {
						notificationManagerCompat.notify(id, builder.build());
					} catch (Exception ex) {
						logger.error("Failed to show fallback notification", ex);
					}
				}
			}
		} catch (Exception e) {
			// catch FileUriExposedException - see https://commonsware.com/blog/2016/09/07/notifications-sounds-android-7p0-aggravation.html
			logger.error("Exception", e);
		}
	}

	@Override
	public void cancel(int id) {
		//make sure that pending intent is also cancelled to allow to check for active conversation notifications pre SDK 23
		Intent intent = new Intent(context, ComposeMessageActivity.class);
		if (id == ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID){
			PendingIntent pendingConversationIntent = PendingIntent.getActivity(context, ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID, intent, this.pendingIntentFlags);
			if (pendingConversationIntent != null){
				pendingConversationIntent.cancel();
			}
		}
		notificationManager.cancel(id);
	}

	@Override
	public void cancel(@NonNull String identity) {
		if (contactService == null) {
			logger.warn("Cannot cancel notification because contact service is null");
			return;
		}

		int uniqueId = contactService.getUniqueId(identity);
		String uniqueIdString = contactService.getUniqueIdString(identity);

		this.cancel(uniqueId, uniqueIdString);
	}

	private void cancel(int uniqueId, @Nullable String uniqueIdString) {
		if (uniqueId != 0) {
			this.cancel(uniqueId);
		}

		//remove all cached notifications from the receiver
		synchronized (this.conversationNotifications) {
			for (Iterator<ConversationNotification> iterator = this.conversationNotifications.iterator(); iterator.hasNext(); ) {
				ConversationNotification conversationNotification = iterator.next();
				if (conversationNotification != null
					&& conversationNotification.getGroup() != null
					&& conversationNotification.getGroup().getMessageReceiver().getUniqueIdString().equals(uniqueIdString)) {
					iterator.remove();
					//call destroy
					this.cancelAndDestroyConversationNotification(conversationNotification);
				}
			}
			showIconBadge(conversationNotifications.size());
		}
		this.cancel(ThreemaApplication.NEW_MESSAGE_NOTIFICATION_ID);
	}

	private int getColorValue(String colorString) {
		int[] colorsHex = context.getResources().getIntArray(R.array.list_light_color_hex);
		if (colorString != null && colorString.length() > 0) {
			return colorsHex[Integer.valueOf(colorString)];
		}

		return -1;
	}

	private void showIconBadge(int unreadMessages) {
		logger.info("Badge: showing " + unreadMessages + " unread");

		if (context.getPackageManager().resolveContentProvider("com.teslacoilsw.notifier", 0) != null) {
			// nova launcher / teslaunread
			try {
				String launcherClassName = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID).getComponent().getClassName();
				final ContentValues contentValues = new ContentValues();
				contentValues.put("tag", BuildConfig.APPLICATION_ID + "/" + launcherClassName);
				contentValues.put("count", unreadMessages);

				context.getApplicationContext().getContentResolver().insert(Uri.parse("content://com.teslacoilsw.notifier/unread_count"), contentValues);
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		} else if (ConfigUtils.isHuaweiDevice()) {
			try {
				String launcherClassName = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID).getComponent().getClassName();
				Bundle localBundle = new Bundle();
				localBundle.putString("package", BuildConfig.APPLICATION_ID);
				localBundle.putString("class", launcherClassName);
				localBundle.putInt("badgenumber", unreadMessages);
				context.getContentResolver().call(Uri.parse("content://com.huawei.android.launcher.settings/badge/"), "change_badge", null, localBundle);
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		} else if (ConfigUtils.isSonyDevice()) {
			try {
				String launcherClassName = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID).getComponent().getClassName();
				if (context.getPackageManager().resolveContentProvider("com.sonymobile.home.resourceprovider", 0) != null) {
					// use content provider
					final ContentValues contentValues = new ContentValues();
					contentValues.put("badge_count", unreadMessages);
					contentValues.put("package_name", BuildConfig.APPLICATION_ID);
					contentValues.put("activity_name", launcherClassName);

					if (RuntimeUtil.isOnUiThread()) {
						if (queryHandler == null) {
							queryHandler = new AsyncQueryHandler(
								context.getApplicationContext().getContentResolver()) {
							};
						}
						queryHandler.startInsert(0, null, Uri.parse("content://com.sonymobile.home.resourceprovider/badge"), contentValues);
					} else {
						context.getApplicationContext().getContentResolver().insert(Uri.parse("content://com.sonymobile.home.resourceprovider/badge"), contentValues);
					}
				} else {
					// use broadcast
					Intent intent = new Intent("com.sonyericsson.home.action.UPDATE_BADGE");
					intent.putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", BuildConfig.APPLICATION_ID);
					intent.putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", launcherClassName);
					intent.putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", String.valueOf(unreadMessages));
					intent.putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", unreadMessages > 0);
					context.sendBroadcast(intent);
				}
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		} else {
			// also works on LG and later HTC devices
			try {
				String launcherClassName = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID).getComponent().getClassName();
				Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
				intent.putExtra("badge_count", unreadMessages);
				intent.putExtra("badge_count_package_name", BuildConfig.APPLICATION_ID);
				intent.putExtra("badge_count_class_name", launcherClassName);
				context.sendBroadcast(intent);
			} catch (Exception e) {
				logger.error("Exception", e);
			}
		}
	}

	@Override
	public void showWebclientResumeFailed(String msg) {
		NotificationCompat.Builder builder =
			new NotificationBuilderWrapper(this.context, NOTIFICATION_CHANNEL_NOTICE, null)
				.setSmallIcon(R.drawable.ic_web_notification)
				.setTicker(msg)
				.setLocalOnly(true)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setCategory(NotificationCompat.CATEGORY_ERROR)
				.setColor(this.context.getResources().getColor(R.color.material_red))
				.setContentTitle(this.context.getString(R.string.app_name))
				.setContentText(msg)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(msg));
		this.notify(ThreemaApplication.WEB_RESUME_FAILED_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_NOTICE);
	}

	@Override
	public void cancelRestartNotification() {
		cancel(APP_RESTART_NOTIFICATION_ID);
	}

	@Override
	public void cancelRestoreNotification() {
		cancel(RESTORE_COMPLETION_NOTIFICATION_ID);
	}

	@Override
	public void showGroupJoinResponseNotification(@NonNull OutgoingGroupJoinRequestModel outgoingGroupJoinRequestModel,
	                                              @NonNull OutgoingGroupJoinRequestModel.Status status,
	                                              @NonNull DatabaseServiceNew databaseService) {
		logger.info("handle join response, showGroupJoinResponseNotification with status {}", status);

		Intent notificationIntent;
		String message;

		switch (status) {
			case ACCEPTED:
				message = String.format(context.getString(R.string.group_response_accepted), outgoingGroupJoinRequestModel.getGroupName());
				break;
			case GROUP_FULL:
				message = String.format(context.getString(R.string.group_response_full), outgoingGroupJoinRequestModel.getGroupName());
				break;
			case REJECTED:
				message = String.format(context.getString(R.string.group_response_rejected), outgoingGroupJoinRequestModel.getGroupName());
				break;
			case UNKNOWN:
			default:
				logger.info("Unknown response state don't show notification");
				return;
		}

		if (outgoingGroupJoinRequestModel.getGroupApiId() != null) {
			GroupModel groupModel = databaseService
				.getGroupModelFactory()
				.getByApiGroupIdAndCreator(outgoingGroupJoinRequestModel.getGroupApiId().toString(), outgoingGroupJoinRequestModel.getAdminIdentity());
			if (groupModel != null) {
				notificationIntent = new Intent(context, ComposeMessageActivity.class);
				notificationIntent.putExtra(ThreemaApplication.INTENT_DATA_GROUP, groupModel.getId());
			} else {
				notificationIntent = new Intent(context, OutgoingGroupRequestActivity.class);
			}
		} else {
			notificationIntent = new Intent(context, OutgoingGroupRequestActivity.class);
		}
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
		PendingIntent openPendingIntent = createPendingIntentWithTaskStack(notificationIntent);

		NotificationSchemaImpl notificationSchema = new NotificationSchemaImpl(this.context);
		notificationSchema
			.setVibrate(this.preferenceService.isVibrate())
			.setColor(this.getColorValue(preferenceService.getNotificationLight()));

		NotificationCompat.Builder builder =
			new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_GROUP_JOIN_RESPONSE, notificationSchema)
				.setSmallIcon(R.drawable.ic_notification_small)
				.setContentTitle(context.getString(R.string.group_response))
				.setContentText(message)
				.setContentIntent(openPendingIntent)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(message))
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setAutoCancel(true);

		this.notify(ThreemaApplication.GROUP_RESPONSE_NOTIFICATION_ID, builder, null, NOTIFICATION_CHANNEL_GROUP_JOIN_RESPONSE);
	}

	@Override
	public void showGroupJoinRequestNotification(@NonNull IncomingGroupJoinRequestModel incomingGroupJoinRequestModel, GroupModel groupModel) {
		if (getContactService() == null) {
			logger.error("Contact service is null; cannot show group join request notification");
			return;
		}

		Intent notificationIntent = new Intent(context, IncomingGroupRequestActivity.class);
		notificationIntent.putExtra(ThreemaApplication.INTENT_DATA_GROUP_API, groupModel.getApiGroupId());
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent openPendingIntent = createPendingIntentWithTaskStack(notificationIntent);

		ContactModel senderContact = this.contactService.getByIdentity(incomingGroupJoinRequestModel.getRequestingIdentity());
		Person.Builder builder = new Person.Builder()
			.setName(NameUtil.getDisplayName(senderContact));

		Bitmap avatar = contactService.getAvatar(senderContact, false);
		if (avatar != null) {
			IconCompat iconCompat = IconCompat.createWithBitmap(avatar);
			builder.setIcon(iconCompat);
		}
		Person senderPerson = builder.build();

		NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle(senderPerson);

		// bug: setting a conversation title implies a group chat
		messagingStyle.setConversationTitle(String.format(context.getString(R.string.group_join_request_for), groupModel.getName()));
		messagingStyle.setGroupConversation(false);

		messagingStyle.addMessage(
			incomingGroupJoinRequestModel.getMessage(),
			incomingGroupJoinRequestModel.getRequestTime().getTime(),
			senderPerson);

		int requestIdNonce = ThreemaApplication.GROUP_REQUEST_NOTIFICATION_ID + (int) SystemClock.elapsedRealtime();

		Intent acceptIntent = new Intent(context, NotificationActionService.class);
		acceptIntent.setAction(NotificationActionService.ACTION_GROUP_REQUEST_ACCEPT);
		acceptIntent.putExtra(ThreemaApplication.INTENT_DATA_INCOMING_GROUP_REQUEST, incomingGroupJoinRequestModel);
		acceptIntent.putExtra(ThreemaApplication.INTENT_DATA_GROUP_REQUEST_NOTIFICATION_ID, requestIdNonce);
		PendingIntent acceptPendingIntent = PendingIntent.getService(context, requestIdNonce + 1, acceptIntent, pendingIntentFlags);

		Intent rejectIntent = new Intent(context, NotificationActionService.class);
		rejectIntent.setAction(NotificationActionService.ACTION_GROUP_REQUEST_REJECT);
		rejectIntent.putExtra(ThreemaApplication.INTENT_DATA_INCOMING_GROUP_REQUEST, incomingGroupJoinRequestModel);
		rejectIntent.putExtra(ThreemaApplication.INTENT_DATA_GROUP_REQUEST_NOTIFICATION_ID, requestIdNonce);
		PendingIntent rejectPendingIntent = PendingIntent.getService(context, requestIdNonce + 2 , rejectIntent, pendingIntentFlags);

		NotificationSchemaImpl notificationSchema = new NotificationSchemaImpl(this.context);
		notificationSchema
			.setVibrate(this.preferenceService.isVibrate())
			.setColor(this.getColorValue(preferenceService.getNotificationLight()));

		NotificationCompat.Builder notifBuilder =
			new NotificationBuilderWrapper(context, NOTIFICATION_CHANNEL_GROUP_JOIN_REQUEST, notificationSchema)
				.setSmallIcon(R.drawable.ic_notification_small)
				.setContentTitle(context.getString(R.string.group_join_request))
				.setContentText(incomingGroupJoinRequestModel.getMessage())
				.setContentIntent(openPendingIntent)
				.setStyle(messagingStyle)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setAutoCancel(true);

		addGroupLinkActions(notifBuilder, acceptPendingIntent, rejectPendingIntent);

		this.notify(requestIdNonce, notifBuilder, null, NOTIFICATION_CHANNEL_GROUP_JOIN_REQUEST);
	}
}
