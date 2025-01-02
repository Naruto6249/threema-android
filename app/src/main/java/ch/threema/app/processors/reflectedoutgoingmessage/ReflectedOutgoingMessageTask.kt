/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2023-2024 Threema GmbH
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

package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.messagereceiver.GroupMessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.processors.reflectedoutgoingmessage.groupcall.ReflectedOutgoingGroupCallStartTask
import ch.threema.base.crypto.Nonce
import ch.threema.base.crypto.NonceScope
import ch.threema.base.utils.LoggingUtil
import ch.threema.base.utils.toHexString
import ch.threema.domain.models.GroupId
import ch.threema.domain.models.MessageId
import ch.threema.protobuf.Common.CspE2eMessageType
import ch.threema.protobuf.d2d.MdD2D.OutgoingMessage
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.MessageState
import java.util.Date

private val logger = LoggingUtil.getThreemaLogger("ReflectedOutgoingMessageTask")

interface ReflectedOutgoingMessageTask {
    fun executeReflectedOutgoingMessageSteps()
}

internal sealed class ReflectedOutgoingBaseMessageTask<M : MessageReceiver<*>>(
    protected val message: OutgoingMessage,
    type: CspE2eMessageType,
    serviceManager: ServiceManager,
) : ReflectedOutgoingMessageTask {
    protected abstract val shouldBumpLastUpdate: Boolean

    protected abstract val messageReceiver: M

    private val nonceFactory by lazy { serviceManager.nonceFactory }

    protected abstract val storeNonces: Boolean

    init {
        if (message.type != type) {
            throw IllegalArgumentException("Incompatible types: ${message.type} - $type")
        }
    }

    override fun executeReflectedOutgoingMessageSteps() {
        if (storeNonces) {
            message.noncesList.forEach {
                val nonce = Nonce(it.toByteArray())
                if (nonceFactory.exists(NonceScope.CSP, nonce)) {
                    logger.info("Skip adding preexisting CSP nonce {}", nonce.bytes.toHexString())
                } else if (!nonceFactory.store(NonceScope.CSP, nonce)) {
                    logger.warn("CSP nonce {} of outgoing message could not be stored", nonce.bytes.toHexString())
                }
            }
        } else {
            logger.debug("Do not store nonces for message of type {}", message.type)
        }

        processOutgoingMessage()

        if (shouldBumpLastUpdate) {
            messageReceiver.bumpLastUpdate()
        }
    }

    protected abstract fun processOutgoingMessage()

    protected fun initializeMessageModelsCommonFields(messageModel: AbstractMessageModel) {
        messageModel.apiMessageId = MessageId(message.messageId).toString()
        messageModel.isSaved = true
        messageModel.isOutbox = true
        messageModel.state = MessageState.SENDING
        messageModel.createdAt = Date(message.createdAt)
    }
}

internal abstract class ReflectedOutgoingContactMessageTask(
    message: OutgoingMessage,
    type: CspE2eMessageType,
    serviceManager: ServiceManager,
) : ReflectedOutgoingBaseMessageTask<ContactMessageReceiver>(message, type, serviceManager) {
    protected val contactService by lazy { serviceManager.contactService }
    protected val contactModelRepository by lazy { serviceManager.modelRepositories.contacts }

    override val messageReceiver: ContactMessageReceiver by lazy {
        val contact = contactService.getByIdentity(message.conversation.contact)
            ?: throw IllegalStateException("The contact of a reflected outgoing message must be known")
        contactService.createReceiver(contact)
    }
}

internal abstract class ReflectedOutgoingGroupMessageTask(
    message: OutgoingMessage,
    type: CspE2eMessageType,
    serviceManager: ServiceManager,
) : ReflectedOutgoingBaseMessageTask<GroupMessageReceiver>(message, type, serviceManager) {
    protected val groupService by lazy { serviceManager.groupService }

    override val messageReceiver: GroupMessageReceiver by lazy {
        val groupIdentity = message.conversation.group
        val group = groupService.getByApiGroupIdAndCreator(
            GroupId(groupIdentity.groupId),
            groupIdentity.creatorIdentity
        ) ?: throw IllegalStateException("The group of a reflected outgoing message must be known")
        groupService.createReceiver(group)
    }
}

fun OutgoingMessage.getReflectedOutgoingMessageTask(
    serviceManager: ServiceManager,
): ReflectedOutgoingMessageTask = when (type) {
    CspE2eMessageType.TEXT -> ReflectedOutgoingTextTask(this, serviceManager)
    CspE2eMessageType.GROUP_TEXT -> ReflectedOutgoingGroupTextTask(this, serviceManager)
    CspE2eMessageType.DELIVERY_RECEIPT -> ReflectedOutgoingDeliveryReceiptTask(this, serviceManager)
    CspE2eMessageType.GROUP_DELIVERY_RECEIPT -> ReflectedOutgoingGroupDeliveryReceiptTask(this, serviceManager)
    CspE2eMessageType.FILE -> ReflectedOutgoingFileTask(this, serviceManager)
    CspE2eMessageType.GROUP_FILE -> ReflectedOutgoingGroupFileTask(this, serviceManager)
    CspE2eMessageType.POLL_SETUP -> throw IllegalStateException("Message type POLL_SETUP for reflected outgoing messages is not implemented yet") // TODO(ANDR-3465)
    CspE2eMessageType.POLL_VOTE -> throw IllegalStateException("Message type POLL_VOTE for reflected outgoing messages is not implemented yet") // TODO(ANDR-3465)
    CspE2eMessageType.GROUP_POLL_SETUP -> throw IllegalStateException("Message type GROUP_POLL_SETUP for reflected outgoing messages is not implemented yet") // TODO(ANDR-3465)
    CspE2eMessageType.GROUP_POLL_VOTE -> throw IllegalStateException("Message type GROUP_POLL_VOTE for reflected outgoing messages is not implemented yet") // TODO(ANDR-3465)
    CspE2eMessageType.GROUP_CALL_START -> ReflectedOutgoingGroupCallStartTask(this, serviceManager)
    CspE2eMessageType.CALL_OFFER,
    CspE2eMessageType.CALL_RINGING,
    CspE2eMessageType.CALL_ANSWER,
    CspE2eMessageType.CALL_HANGUP -> ReflectedOutgoingPlaceholderTask(
        message = this,
        serviceManager = serviceManager,
        logMessage = "Reflected message of type ${type.name} was received as outgoing"
    )

    CspE2eMessageType.CALL_ICE_CANDIDATE -> throw IllegalStateException("Reflected message of type ${type.name} should never be received as outgoing")
    CspE2eMessageType.CONTACT_REQUEST_PROFILE_PICTURE -> ReflectedOutgoingContactRequestProfilePictureTask(this, serviceManager)
    CspE2eMessageType.CONTACT_SET_PROFILE_PICTURE -> ReflectedOutgoingContactSetProfilePictureTask(this, serviceManager)
    CspE2eMessageType.CONTACT_DELETE_PROFILE_PICTURE -> ReflectedOutgoingDeleteProfilePictureTask(this, serviceManager)

    else -> throw IllegalStateException("Unknown message type $type")
}
