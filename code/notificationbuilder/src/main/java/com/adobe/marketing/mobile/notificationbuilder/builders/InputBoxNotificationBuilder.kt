/*
  Copyright 2024 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.notificationbuilder.builders

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.adobe.marketing.mobile.notificationbuilder.NotificationConstructionFailedException
import com.adobe.marketing.mobile.notificationbuilder.PushTemplateConstants
import com.adobe.marketing.mobile.notificationbuilder.PushTemplateImageUtils
import com.adobe.marketing.mobile.notificationbuilder.R
import com.adobe.marketing.mobile.notificationbuilder.extensions.createNotificationChannelIfRequired
import com.adobe.marketing.mobile.notificationbuilder.templates.InputBoxPushTemplate
import com.adobe.marketing.mobile.services.Log

/**
 * Object responsible for constructing a [NotificationCompat.Builder] object containing an input box push template notification.
 */
internal object InputBoxNotificationBuilder {
    private const val SELF_TAG = "InputBoxNotificationBuilder"

    @Throws(NotificationConstructionFailedException::class)
    fun construct(
        context: Context,
        pushTemplate: InputBoxPushTemplate,
        trackerActivityClass: Class<out Activity>?,
        broadcastReceiverClass: Class<out BroadcastReceiver>?
    ): NotificationCompat.Builder {
        Log.trace(
            PushTemplateConstants.LOG_TAG,
            SELF_TAG,
            "Building an input box template push notification."
        )
        val packageName = context.packageName
        val smallLayout = RemoteViews(packageName, R.layout.push_template_collapsed)
        val expandedLayout = RemoteViews(packageName, R.layout.push_template_expanded)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelIdToUse: String = notificationManager.createNotificationChannelIfRequired(
            context,
            pushTemplate.channelId,
            pushTemplate.sound,
            pushTemplate.getNotificationImportance(),
            pushTemplate.isFromIntent
        )

        // create the notification builder with the common settings applied
        val notificationBuilder = AEPPushNotificationBuilder.construct(
            context,
            pushTemplate,
            channelIdToUse,
            trackerActivityClass,
            smallLayout,
            expandedLayout,
            R.id.basic_expanded_layout
        )

        // get push payload data. if we are handling an intent then we know that we should be building a feedback received notification.
        val imageUri =
            if (pushTemplate.isFromIntent) pushTemplate.feedbackImage else pushTemplate.imageUrl
        val downloadedImageCount = PushTemplateImageUtils.cacheImages(listOf(imageUri))

        if (downloadedImageCount == 1) {
            val pushImage = PushTemplateImageUtils.getCachedImage(imageUri)
            expandedLayout.setImageViewBitmap(R.id.expanded_template_image, pushImage)
        } else {
            Log.trace(
                PushTemplateConstants.LOG_TAG,
                SELF_TAG,
                "No image found for input box push template."
            )
            expandedLayout.setViewVisibility(R.id.expanded_template_image, View.GONE)
        }

        val expandedBodyText =
            if (pushTemplate.isFromIntent) pushTemplate.feedbackText else pushTemplate.expandedBodyText
        val collapsedBodyText =
            if (pushTemplate.isFromIntent) pushTemplate.feedbackText else pushTemplate.body
        smallLayout.setTextViewText(R.id.notification_title, pushTemplate.title)
        smallLayout.setTextViewText(R.id.notification_body, collapsedBodyText)
        expandedLayout.setTextViewText(R.id.notification_title, pushTemplate.title)
        expandedLayout.setTextViewText(
            R.id.notification_body_expanded, expandedBodyText
        )

        // add an input box to capture user feedback if the push template is not from an intent
        // otherwise, we are done building the notification
        if (pushTemplate.isFromIntent) {
            return notificationBuilder
        }
        Log.trace(
            PushTemplateConstants.LOG_TAG,
            SELF_TAG,
            "Adding an input box to capture text input. The input box receiver name is ${pushTemplate.inputBoxReceiverName}."
        )
        addInputTextAction(
            context,
            broadcastReceiverClass,
            notificationBuilder,
            channelIdToUse,
            pushTemplate
        )

        return notificationBuilder
    }

    /**
     * Adds an input text action for the notification.
     *
     * @param context the application [Context]
     * @param broadcastReceiverClass the [BroadcastReceiver] class to use as the broadcast receiver
     * @param builder the [NotificationCompat.Builder] to attach the action buttons
     * @param channelId the [String] containing the channel ID to use for the notification
     * @param pushTemplate the [InputBoxPushTemplate] object containing the input box push template data
     * button is pressed
     */
    private fun addInputTextAction(
        context: Context,
        broadcastReceiverClass: Class<out BroadcastReceiver>?,
        builder: NotificationCompat.Builder,
        channelId: String,
        pushTemplate: InputBoxPushTemplate
    ) {
        val inputHint =
            if (pushTemplate.inputTextHint.isNullOrEmpty()) PushTemplateConstants.DefaultValues.INPUT_BOX_DEFAULT_REPLY_TEXT else pushTemplate.inputTextHint
        val remoteInput = RemoteInput.Builder(pushTemplate.inputBoxReceiverName)
            .setLabel(inputHint)
            .build()

        val inputReceivedIntent = createInputReceivedIntent(
            context,
            broadcastReceiverClass,
            channelId,
            pushTemplate
        )

        val replyPendingIntent =
            inputReceivedIntent?.let {
                PendingIntent.getBroadcast(
                    context,
                    pushTemplate.tag.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            }

        val action =
            NotificationCompat.Action.Builder(null, inputHint, replyPendingIntent)
                .addRemoteInput(remoteInput)
                .build()

        builder.addAction(action)
    }

    private fun createInputReceivedIntent(
        context: Context,
        broadcastReceiverClass: Class<out BroadcastReceiver>?,
        channelId: String,
        pushTemplate: InputBoxPushTemplate
    ): Intent? {
        if (broadcastReceiverClass == null) {
            return null
        }
        Log.trace(
            PushTemplateConstants.LOG_TAG,
            SELF_TAG,
            "Creating a text input received intent from a push template object."
        )

        val inputReceivedIntent = Intent(PushTemplateConstants.IntentActions.INPUT_RECEIVED)
        broadcastReceiverClass.let {
            inputReceivedIntent.setClass(context.applicationContext, broadcastReceiverClass)
        }

        inputReceivedIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.TEMPLATE_TYPE, pushTemplate.templateType?.value
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.IMAGE_URI, pushTemplate.imageUrl
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.ACTION_URI, pushTemplate.actionUri
        )
        inputReceivedIntent.putExtra(PushTemplateConstants.IntentKeys.CHANNEL_ID, channelId)
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.CUSTOM_SOUND, pushTemplate.sound
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.TITLE_TEXT,
            pushTemplate.title
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.BODY_TEXT,
            pushTemplate.body
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.EXPANDED_BODY_TEXT,
            pushTemplate.expandedBodyText
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.NOTIFICATION_BACKGROUND_COLOR,
            pushTemplate.notificationBackgroundColor
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.TITLE_TEXT_COLOR,
            pushTemplate.titleTextColor
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.EXPANDED_BODY_TEXT_COLOR,
            pushTemplate.expandedBodyTextColor
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.SMALL_ICON, pushTemplate.smallIcon
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.SMALL_ICON_COLOR,
            pushTemplate.smallIconColor
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.LARGE_ICON, pushTemplate.largeIcon
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.VISIBILITY,
            pushTemplate.getNotificationVisibility()
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.IMPORTANCE,
            pushTemplate.getNotificationImportance()
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.BADGE_COUNT, pushTemplate.badgeCount
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.INPUT_BOX_FEEDBACK_TEXT, pushTemplate.feedbackText
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.INPUT_BOX_FEEDBACK_IMAGE, pushTemplate.feedbackImage
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.INPUT_BOX_RECEIVER_NAME,
            pushTemplate.inputBoxReceiverName
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.STICKY, pushTemplate.isNotificationSticky
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.TAG, pushTemplate.tag
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.TICKER, pushTemplate.ticker
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.PAYLOAD_VERSION, pushTemplate.payloadVersion
        )
        inputReceivedIntent.putExtra(
            PushTemplateConstants.IntentKeys.PRIORITY,
            pushTemplate.notificationPriority
        )

        return inputReceivedIntent
    }
}