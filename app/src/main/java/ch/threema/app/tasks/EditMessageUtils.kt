/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2024 Threema GmbH
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

package ch.threema.app.tasks

import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.services.MessageService
import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.models.MessageId
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import ch.threema.domain.protocol.csp.messages.EditMessage
import ch.threema.domain.protocol.csp.messages.GroupEditMessage
import ch.threema.storage.models.AbstractMessageModel
import org.slf4j.Logger

private val logger: Logger = LoggingUtil.getThreemaLogger("EditMessageUtils")

fun runCommonEditMessageReceiveSteps(
    editMessage: EditMessage,
    receiver: MessageReceiver<*>,
    messageService: MessageService
) : AbstractMessageModel? {
    return runCommonEditMessageReceiveSteps(editMessage, editMessage.data.messageId, receiver, messageService)
}

fun runCommonEditMessageReceiveSteps(
    editMessage: GroupEditMessage,
    receiver: MessageReceiver<*>,
    messageService: MessageService
) : AbstractMessageModel? {
    return runCommonEditMessageReceiveSteps(editMessage, editMessage.data.messageId, receiver, messageService)
}

private fun runCommonEditMessageReceiveSteps(
    editMessage: AbstractMessage,
    messageId: Long,
    receiver: MessageReceiver<*>,
    messageService: MessageService
) : AbstractMessageModel? {
    val apiMessageId = MessageId(messageId).toString()
    val message = messageService.getMessageModelByApiMessageIdAndReceiver(apiMessageId, receiver)

    if (message == null) {
        logger.warn("Incoming Edit Message: No message found for id: $apiMessageId")
        return null
    }
    if (editMessage.fromIdentity != message.identity) {
        logger.error("Incoming Edit Message: original message's sender ${message.identity} does not equal edited message's sender ${editMessage.fromIdentity}")
        return null
    }

    message.editedAt = editMessage.date

    return message
}
